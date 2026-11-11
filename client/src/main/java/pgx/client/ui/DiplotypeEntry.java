package pgx.client.ui;

import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import pgx.client.PgxEngine;
import pgx.client.PgxReport;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Picks a gene and a diplotype, and collects the chosen pairs.
 *
 * The diplotype list is searched, never listed whole: CYP2D6 holds 16,836, so
 * the control asks the engine for a narrowed page and says how many matched.
 * Every lookup runs off the UI thread.
 */
public class DiplotypeEntry extends VBox {

    private static final String MONO = "monospace";
    private static final String INK = "#f2f3f5";
    private static final String MUTED = "#9aa1ab";
    private static final String YELLOW = "#f2cf4b";
    private static final int PAGE = 40;

    private final PgxEngine engine;
    private final Consumer<String> onError;

    private final ComboBox<String> genes = new ComboBox<>();
    private final TextField search = new TextField();
    private final ListView<PgxReport.DiplotypeOption> matches = new ListView<>();
    private final Label matchCount = new Label();
    private final ListView<String> chosen = new ListView<>();
    private final Map<String, String> selected = new LinkedHashMap<>();

    public DiplotypeEntry(PgxEngine engine, Consumer<String> onError) {
        this.engine = engine;
        this.onError = onError;

        setSpacing(10);
        setPadding(new Insets(20));
        setPrefWidth(380);

        genes.setPromptText("gene");
        genes.setPrefWidth(150);
        genes.setOnAction(event -> runSearch());

        search.setPromptText("filter, e.g. *4/");
        search.setPrefWidth(200);
        search.textProperty().addListener((observable, before, after) -> runSearch());

        matches.setPrefHeight(230);
        matches.setCellFactory(view -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(PgxReport.DiplotypeOption item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.diplotype() + "   " + nullSafe(item.phenotype()));
                }
                setFont(Font.font(MONO, 12));
            }
        });
        matches.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                add();
            }
        });

        matchCount.setFont(Font.font(MONO, 11));
        matchCount.setWrapText(true);
        matchCount.setMaxWidth(340);
        matchCount.setStyle("-fx-text-fill: " + MUTED + ";");

        Button addButton = new Button("Add");
        addButton.setOnAction(event -> add());

        Button clearButton = new Button("Clear");
        clearButton.setOnAction(event -> {
            selected.clear();
            refreshChosen();
        });

        chosen.setPrefHeight(110);
        chosen.setStyle("-fx-font-family: '" + MONO + "';");

        getChildren().addAll(
                heading("// SELECT"),
                new HBox(8, genes, search),
                matches,
                matchCount,
                new HBox(8, addButton, clearButton),
                heading("// CHOSEN"),
                chosen);

        loadGenes();
    }

    /** The diplotypes picked so far, as the engine expects them. */
    public Map<String, String> diplotypes() {
        return new LinkedHashMap<>(selected);
    }

    private void loadGenes() {
        Task<java.util.List<String>> task = new Task<>() {
            @Override
            protected java.util.List<String> call() throws Exception {
                return engine.availableGenes();
            }
        };
        task.setOnSucceeded(event -> {
            genes.setItems(FXCollections.observableArrayList(task.getValue()));
            if (!genes.getItems().isEmpty()) {
                genes.getSelectionModel().selectFirst();
            }
        });
        task.setOnFailed(event -> onError.accept(message(task)));
        run(task);
    }

    private void runSearch() {
        String gene = genes.getSelectionModel().getSelectedItem();
        if (gene == null) {
            return;
        }
        String query = search.getText();

        Task<PgxReport.DiplotypeSearch> task = new Task<>() {
            @Override
            protected PgxReport.DiplotypeSearch call() throws Exception {
                return engine.searchDiplotypes(gene, query, PAGE);
            }
        };
        task.setOnSucceeded(event -> {
            PgxReport.DiplotypeSearch result = task.getValue();
            matches.setItems(FXCollections.observableArrayList(result.diplotypes()));
            matchCount.setText(result.truncated()
                    ? "showing " + result.diplotypes().size() + " of "
                        + result.total() + " — narrow the filter"
                    : result.total() + " matching");
        });
        task.setOnFailed(event -> onError.accept(message(task)));
        run(task);
    }

    private void add() {
        String gene = genes.getSelectionModel().getSelectedItem();
        PgxReport.DiplotypeOption option = matches.getSelectionModel().getSelectedItem();

        if (gene == null || option == null) {
            onError.accept("Pick a gene and a diplotype first.");
            return;
        }
        selected.put(gene, option.diplotype());
        refreshChosen();
    }

    private void refreshChosen() {
        chosen.setItems(FXCollections.observableArrayList(
                selected.entrySet().stream()
                        .map(entry -> entry.getKey() + "  " + entry.getValue())
                        .toList()));
    }

    private static void run(Task<?> task) {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    private static String message(Task<?> task) {
        Throwable error = task.getException();
        return error == null ? "Unknown error." : error.getMessage();
    }

    private Label heading(String text) {
        Label label = new Label(text);
        label.setFont(Font.font(MONO, 11));
        label.setStyle("-fx-text-fill: " + YELLOW + ";");
        return label;
    }

    private static String nullSafe(String value) {
        return value == null ? "—" : value;
    }
}
