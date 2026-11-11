package pgx.client.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import pgx.client.HttpPgxEngine;
import pgx.client.PgxEngine;
import pgx.client.PgxReport;
import pgx.client.StubPgxEngine;

import java.util.Map;

/**
 * PGx Engine — a reader for one patient's report.
 *
 * Educational use only. Not clinical decision support.
 *
 * The window depends on {@link PgxEngine} and never on a transport, so the
 * engine can be swapped between the live service and the offline stub while
 * the application is running. That switch is in the toolbar, which makes the
 * stub a demonstrable fallback rather than a development convenience.
 */
public class PgxApp extends Application {

    private static final String MONO = "monospace";
    private static final String BACKGROUND = "#22262b";
    private static final String INK = "#f2f3f5";
    private static final String MUTED = "#9aa1ab";
    private static final String PINK = "#ef5680";

    private static final String LIVE = "Live engine (HTTP)";
    private static final String OFFLINE = "Offline stub";

    private PgxEngine engine = new StubPgxEngine();

    private BorderPane root;
    private DiplotypeEntry entry;
    private ReportView reportView;
    private Label status;

    @Override
    public void start(Stage stage) {
        reportView = new ReportView(this::openLink);
        status = new Label();
        status.setFont(Font.font(MONO, 11));
        status.setStyle("-fx-text-fill: " + MUTED + ";");

        root = new BorderPane();
        root.setStyle(
                "-fx-base: " + BACKGROUND + ";"
                        + "-fx-background: " + BACKGROUND + ";"
                        + "-fx-font-family: '" + MONO + "';"
                        + "-fx-focus-color: transparent;"
                        + "-fx-faint-focus-color: transparent;");

        root.setTop(toolbar());
        root.setBottom(statusBar());
        buildEntry();

        ScrollPane scroller = new ScrollPane(reportView);
        scroller.setFitToWidth(true);
        scroller.setStyle(
                "-fx-background-color: " + BACKGROUND + ";"
                        + "-fx-background: " + BACKGROUND + ";"
                        + "-fx-border-color: transparent;");
        root.setCenter(scroller);

        stage.setTitle("PGx Engine — educational use only");
        stage.setScene(new Scene(root, 1120, 780));
        stage.show();

        setStatus("Offline stub. Nothing is contacted until you switch to the live engine.");
    }

    private Region toolbar() {
        Label title = new Label("PGX_ENGINE");
        title.setFont(Font.font(MONO, 13));
        title.setStyle("-fx-text-fill: " + INK + ";");

        ComboBox<String> source = new ComboBox<>();
        source.getItems().addAll(OFFLINE, LIVE);
        source.getSelectionModel().select(OFFLINE);
        source.setOnAction(event -> switchEngine(source.getValue()));

        Button runButton = new Button("Run report");
        runButton.setOnAction(event -> runReport());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(14, title, spacer, source, runButton);
        bar.setPadding(new Insets(12, 18, 12, 18));
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private Region statusBar() {
        HBox bar = new HBox(status);
        bar.setPadding(new Insets(8, 18, 10, 18));
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private void buildEntry() {
        entry = new DiplotypeEntry(engine, this::setError);
        HBox left = new HBox(entry, new Separator(javafx.geometry.Orientation.VERTICAL));
        root.setLeft(left);
    }

    private void switchEngine(String choice) {
        if (LIVE.equals(choice)) {
            HttpPgxEngine live = new HttpPgxEngine();
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
            setError("Choose at least one gene and diplotype first.");
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

    private void setStatus(String message) {
        Platform.runLater(() -> {
            status.setText(message);
            status.setStyle("-fx-text-fill: " + MUTED + ";");
        });
    }

    private void setError(String message) {
        Platform.runLater(() -> {
            status.setText(message);
            status.setStyle("-fx-text-fill: " + PINK + ";");
        });
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
