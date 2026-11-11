package pgx.client;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts and stops the bundled Python engine.
 *
 * In a distribution the engine is a folder beside the application, holding an
 * executable and the knowledge base it reads. The client starts it on a port
 * nobody else is using, waits for it to answer, and kills it on the way out —
 * so whoever runs the application needs neither Python nor a terminal.
 *
 * Absent entirely during development, where the engine is normally already
 * running under uvicorn. {@link #locate()} returns null and the caller falls
 * back to whatever is listening on the default port.
 */
public final class EngineProcess implements AutoCloseable {

    /** Overrides the search, for a layout this class does not anticipate. */
    private static final String HOME_PROPERTY = "pgx.engine.home";

    private static final String FOLDER = "engine";
    private static final Duration READY_TIMEOUT = Duration.ofSeconds(40);
    private static final Duration POLL = Duration.ofMillis(250);

    private final Process process;
    private final String baseUrl;
    private final Path log;
    private final Thread shutdownHook;

    private EngineProcess(Process process, String baseUrl, Path log) {
        this.process = process;
        this.baseUrl = baseUrl;
        this.log = log;

        // A JVM that dies without reaching close() must not leave the engine
        // running and the port held.
        this.shutdownHook = new Thread(() -> stop(process));
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    // ------------------------------------------------------------------

    /** The bundled engine executable, or null when this is not a distribution. */
    public static Path locate() {
        String name = isWindows() ? "pgx-engine.exe" : "pgx-engine";

        for (Path root : searchRoots()) {
            Path candidate = root.resolve(FOLDER).resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Starts the engine and returns once it answers, or throws.
     *
     * @throws IOException if it cannot be started, dies during startup, or
     *                     does not answer within the timeout
     */
    public static EngineProcess start(Path executable) throws IOException {
        int port = freePort();
        Path log = Paths.get(System.getProperty("java.io.tmpdir"), "pgx-engine.log");

        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add("--host");
        command.add("127.0.0.1");
        command.add("--port");
        command.add(Integer.toString(port));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(executable.getParent().toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());

        Process process = builder.start();
        String baseUrl = "http://127.0.0.1:" + port;

        if (!waitUntilReady(process, baseUrl)) {
            stop(process);
            throw new IOException(
                    "The bundled engine did not start. See " + log + " for what it said.");
        }
        return new EngineProcess(process, baseUrl, log);
    }

    public String baseUrl() {
        return baseUrl;
    }

    public Path logFile() {
        return log;
    }

    @Override
    public void close() {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // Already shutting down; the hook is running or has run.
        }
        stop(process);
    }

    // ------------------------------------------------------------------

    /**
     * Where a distribution might have put the engine folder.
     *
     * A runnable jar sits beside it. A jpackage app-image puts the jar in
     * app/, one level below the launcher, so the grandparent is checked too.
     */
    private static List<Path> searchRoots() {
        List<Path> roots = new ArrayList<>();

        String override = System.getProperty(HOME_PROPERTY);
        if (override != null && !override.isBlank()) {
            roots.add(Paths.get(override));
        }

        Path code = codeSourceDirectory();
        if (code != null) {
            roots.add(code);
            if (code.getParent() != null) {
                roots.add(code.getParent());
                if (code.getParent().getParent() != null) {
                    roots.add(code.getParent().getParent());
                }
            }
        }
        roots.add(Paths.get(System.getProperty("user.dir", ".")));
        return roots;
    }

    private static Path codeSourceDirectory() {
        try {
            URI uri = EngineProcess.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            Path path = Paths.get(uri);
            return Files.isDirectory(path) ? path : path.getParent();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A port the OS says is free.
     *
     * Opening and closing a socket to learn a number leaves a gap in which
     * something else could take it. The gap is microseconds and the engine is
     * the only thing being started, so the alternative — a fixed port that
     * collides with whatever else the user is running — is the worse bet.
     */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static boolean waitUntilReady(Process process, String baseUrl) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();

        long deadline = System.nanoTime() + READY_TIMEOUT.toNanos();

        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                return false;
            }
            try {
                HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (IOException e) {
                // Not up yet. A frozen engine takes a few seconds to unpack.
            }
            try {
                Thread.sleep(POLL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static void stop(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
