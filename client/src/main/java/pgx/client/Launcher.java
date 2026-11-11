package pgx.client;

import pgx.client.ui.PgxApp;

/**
 * The main class a packaged build starts from.
 *
 * It exists because of one JavaFX rule: when the runtime is found on the
 * classpath rather than the module path, a main class that extends
 * Application is refused with "JavaFX runtime components are missing". A
 * class that does not extend it is allowed to start the toolkit. So this
 * holds the manifest's Main-Class and hands straight over.
 *
 * Development still runs PgxApp directly through javafx:run, where the
 * modules are on the module path and the rule does not apply.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        PgxApp.main(args);
    }
}
