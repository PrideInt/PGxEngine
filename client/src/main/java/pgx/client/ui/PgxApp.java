package pgx.client.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import pgx.client.EngineProcess;
import pgx.client.HttpPgxEngine;
import pgx.client.PgxEngine;
import pgx.client.PgxReport;
import pgx.client.StubPgxEngine;

import java.nio.file.Path;
import java.util.Map;

/**
 * PGx Engine — a reader for one patient's report.
 *
 * Educational use only. Not clinical decision support.
 *
 * The window depends on {@link PgxEngine} and never on a transport, so the
 * engine can be swapped between the live service and the offline stub while
 * the application is running. That switch is in the header, which makes the
 * stub a demonstrable fallback rather than a development convenience.
 *
 * Nothing here sets a colour or a font. Those live in pgx.css.
 */
public class PgxApp extends Application {

    private static final String LIVE = "Live engine (HTTP)";
    private static final String OFFLINE = "Offline stub";

    private PgxEngine engine = new StubPgxEngine();

    /** Set on a background thread, read on the FX thread. */
    private volatile EngineProcess bundled;

    /** Where the live engine is. Replaced by the bundled engine's own port. */
    private volatile String liveUrl = "http://127.0.0.1:8000";

    private ComboBox<String> source;
    private BorderPane root;
    private DiplotypeEntry entry;
    private ReportView reportView;
    private Button runButton;
    private Label status;

    @Override
    public void start(Stage stage) {
        reportView = new ReportView(this::openLink);

        status = new Label();
        status.getStyleClass().add("status");

        root = new BorderPane();
        root.setTop(header());
        root.setBottom(statusBar());
        root.setCenter(reportPane());
        buildEntry();

        Scene scene = new Scene(root, 1240, 820);
        Theme.apply(scene);

        stage.setTitle("PGx Engine — educational use only");
        stage.getIcons().addAll(Theme.appIcons());
        stage.setScene(scene);
        stage.show();

        setStatus("Offline stub. Looking for an engine…");
        bootstrapEngine();
    }

    /** Closes the engine this application started. Not one it merely found. */
    @Override
    public void stop() {
        EngineProcess started = bundled;
        if (started != null) {
            started.close();
        }
    }

    /**
     * Finds an engine without being asked.
     *
     * A distribution ships one beside the application, and starting it is the
     * difference between an application and a thing that needs a terminal
     * open next to it. Failing that, something may already be listening on
     * the default port, which is the development case. Failing both, the stub
     * stands and the application still opens.
     */
    private void bootstrapEngine() {
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                Path executable = EngineProcess.locate();
                if (executable != null) {
                    EngineProcess started = EngineProcess.start(executable);
                    bundled = started;
                    return started.baseUrl();
                }
                return new HttpPgxEngine(liveUrl).healthy() ? liveUrl : null;
            }
        };
        task.setOnSucceeded(event -> {
            String url = task.getValue();
            if (url == null) {
                setStatus("Offline stub. No engine found, so nothing is contacted.");
                return;
            }
            liveUrl = url;
            engine = new HttpPgxEngine(url);
            buildEntry();
            selectQuietly(LIVE);
            setStatus(bundled == null
                    ? "Live engine at " + url + "."
                    : "Engine started at " + url + ".");
        });
        task.setOnFailed(event -> {
            Throwable error = task.getException();
            setError((error == null ? "The engine could not be started." : error.getMessage())
                    + " Staying on the offline stub.");
        });
        run(task);
    }

    /** Moves the dropdown without re-entering switchEngine. */
    private void selectQuietly(String choice) {
        source.setOnAction(null);
        source.getSelectionModel().select(choice);
        source.setOnAction(event -> switchEngine(source.getValue()));
    }

    // ------------------------------------------------------------------

    private Region header() {
        Label title = new Label("PGX_ENGINE");
        title.getStyleClass().add("app-title");

        Label subtitle = new Label("CPIC guideline lookup · educational use only");
        subtitle.getStyleClass().add("app-subtitle");

        VBox naming = new VBox(1, title, subtitle);

        HBox branding = new HBox(14);
        branding.setAlignment(Pos.CENTER_LEFT);

        // Absent by default. See Theme.logo.
        ImageView logo = Theme.logo(34);
        if (logo != null) {
            Region divider = new Region();
            divider.getStyleClass().add("brand-divider");
            branding.getChildren().addAll(logo, divider);
        }
        branding.getChildren().add(naming);

        source = new ComboBox<>();
        source.getItems().addAll(OFFLINE, LIVE);
        source.getSelectionModel().select(OFFLINE);
        source.setOnAction(event -> switchEngine(source.getValue()));

        runButton = new Button("Run report");
        runButton.getStyleClass().add("primary");
        runButton.setDisable(true);
        runButton.setOnAction(event -> runReport());

        HBox bar = new HBox(14, branding, grow(), source, runButton);
        bar.getStyleClass().add("shell-header");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private Region reportPane() {
        ScrollPane scroller = new ScrollPane(reportView);
        scroller.setFitToWidth(true);
        return scroller;
    }

    private Region statusBar() {
        // Authorship, not endorsement. A course and an institution are facts
        // about who wrote this; they are not a claim that anyone approved it.
        Label attribution = new Label("BMI 530 · Stony Brook University");
        attribution.getStyleClass().add("attribution");

        HBox bar = new HBox(status, grow(), attribution);
        bar.getStyleClass().add("status-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private void buildEntry() {
        entry = new DiplotypeEntry(engine, this::setError);
        entry.setOnChange(() -> runButton.setDisable(!entry.hasSelection()));
        runButton.setDisable(true);
        root.setLeft(entry);
    }

    // ------------------------------------------------------------------

    private void switchEngine(String choice) {
        if (LIVE.equals(choice)) {
            HttpPgxEngine live = new HttpPgxEngine(liveUrl);
            Task<Boolean> probe = new Task<>() {
                @Override
                protected Boolean call() {
                    return live.healthy();
                }
            };
            probe.setOnSucceeded(event -> {
                if (Boolean.TRUE.equals(probe.getValue())) {
                    engine = live;
                    buildEntry();
                    setStatus("Live engine at " + live.baseUrl() + ".");
                } else {
                    setError("No engine answered at " + live.baseUrl()
                            + ". Staying on the offline stub.");
                }
            });
            run(probe);
        } else {
            engine = new StubPgxEngine();
            buildEntry();
            setStatus("Offline stub.");
        }
    }

    private void runReport() {
        Map<String, String> diplotypes = entry.diplotypes();

        if (diplotypes.isEmpty()) {
            setError("Choose a diplotype for at least one gene first.");
            return;
        }
        setStatus("Running…");

        PgxEngine current = engine;
        Task<PgxReport> task = new Task<>() {
            @Override
            protected PgxReport call() throws Exception {
                return current.report(diplotypes, null);
            }
        };
        task.setOnSucceeded(event -> {
            PgxReport report = task.getValue();
            reportView.show(report);
            setStatus(describe(report));
        });
        task.setOnFailed(event -> {
            Throwable error = task.getException();
            String message = error == null ? "Unknown error." : error.getMessage();
            reportView.showMessage("The report could not be produced.\n\n" + message);
            setError(message);
        });
        run(task);
    }

    private String describe(PgxReport report) {
        long resolved = report.genes() == null ? 0
                : report.genes().stream().filter(PgxReport.GeneResult::resolved).count();
        long abstentions = report.drugs() == null ? 0
                : report.drugs().stream()
                        .filter(PgxReport.DrugRecommendation::isAbstention).count();

        int genes = report.genes() == null ? 0 : report.genes().size();
        int drugs = report.drugs() == null ? 0 : report.drugs().size();

        return resolved + " of " + genes + " genes resolved · " + drugs
                + " drugs · " + abstentions + " where the guideline declines to advise";
    }

    private void openLink(String url) {
        try {
            getHostServices().showDocument(url);
        } catch (Exception e) {
            setError("Could not open " + url);
        }
    }

    // ------------------------------------------------------------------

    private void setStatus(String message) {
        Platform.runLater(() -> {
            status.setText(message);
            status.getStyleClass().setAll("status");
        });
    }

    private void setError(String message) {
        Platform.runLater(() -> {
            status.setText(message);
            status.getStyleClass().setAll("status-error");
        });
    }

    private static Region grow() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private static void run(Task<?> task) {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
