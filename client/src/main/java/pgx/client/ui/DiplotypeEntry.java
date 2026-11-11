package pgx.client.ui;

import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import pgx.client.PgxEngine;
import pgx.client.PgxReport;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Picks one diplotype per gene.
 *
 * The genes are a segmented row rather than a dropdown, and each tab carries
 * the diplotype chosen for it. That is the whole selection, visible at once,
 * in the space a dropdown alone used to take — so there is no second list of
 * "chosen" rows to keep in step, and the match list gets the rail's full
 * height instead of a fixed 230px.
 *
 * The diplotype list is searched, never listed whole: CYP2D6 holds 16,836, so
 * the control asks the engine for a narrowed page and says how many matched.
 * Every lookup runs off the UI thread.
 */
public class DiplotypeEntry extends VBox {

    private static final int PAGE = 100;

    private final PgxEngine engine;
    private final Consumer<String> onError;

    private final HBox geneTabs = new HBox(6);
    private final ToggleGroup geneGroup = new ToggleGroup();
    private final TextField search = new TextField();
    private final ListView<PgxReport.DiplotypeOption> matches = new ListView<>();
    private final Label matchCount = new Label();

    private final Map<String, String> selected = new LinkedHashMap<>();
    private final Map<String, Label> values = new LinkedHashMap<>();

    private Runnable onChange = () -> { };

    public DiplotypeEntry(PgxEngine engine, Consumer<String> onError) {
        this.engine = engine;
        this.onError = onError;

        getStyleClass().add("rail");
        setPrefWidth(340);
        setMinWidth(300);

        search.setPromptText("filter, e.g. *4/");
        search.textProperty().addListener((observable, before, after) -> runSearch());

        matches.setCellFactory(view -> new OptionCell());
        matches.getSelectionModel().selectedItemProperty()
                .addListener((observable, before, after) -> choose(after));
        VBox.setVgrow(matches, Priority.ALWAYS);

        matchCount.getStyleClass().add("match-count");
        matchCount.setWrapText(true);

        // A toggle group lets you deselect the active toggle by clicking it.
        // One gene must always be current, so that is refused.
        geneGroup.selectedToggleProperty().addListener((observable, before, after) -> {
            if (after == null) {
                if (before != null) {
                    before.setSelected(true);
                }
                return;
            }
            if (search.getText().isEmpty()) {
                runSearch();
            } else {
                search.clear();
            }
        });

        Button clear = new Button("Clear all");
        clear.getStyleClass().add("quiet");
        clear.setOnAction(event -> clearAll());

        HBox footer = new HBox(matchCount, grow(), clear);
        footer.setAlignment(Pos.CENTER_LEFT);

        getChildren().addAll(
                label("// GENOTYPE", "section-label"),
                geneTabs,
                search,
                matches,
                footer);

        loadGenes();
    }

    // ------------------------------------------------------------------

    /** The diplotypes picked so far, as the engine expects them. */
    public Map<String, String> diplotypes() {
        return new LinkedHashMap<>(selected);
    }

    public boolean hasSelection() {
        return !selected.isEmpty();
    }

    /** Called whenever the selection changes, so the shell can enable Run. */
    public void setOnChange(Runnable onChange) {
        this.onChange = onChange == null ? () -> { } : onChange;
    }

    // ------------------------------------------------------------------

    private void loadGenes() {
        Task<List<String>> task = new Task<>() {
            @Override
            protected List<String> call() throws Exception {
                return engine.availableGenes();
            }
        };
        task.setOnSucceeded(event -> buildTabs(task.getValue()));
        task.setOnFailed(event -> onError.accept(message(task)));
        run(task);
    }

    private void buildTabs(List<String> genes) {
        geneTabs.getChildren().clear();
        values.clear();

        for (String gene : genes) {
            Label name = label(gene, "gene-name");
            Label value = label("—", "gene-value");
            value.getStyleClass().add("unset");
            values.put(gene, value);

            VBox stack = new VBox(2, name, value);
            stack.setAlignment(Pos.CENTER);

            ToggleButton tab = new ToggleButton();
            tab.getStyleClass().add("gene-tab");
            tab.setGraphic(stack);
            tab.setUserData(gene);
            tab.setToggleGroup(geneGroup);
            tab.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(tab, Priority.ALWAYS);

            geneTabs.getChildren().add(tab);
        }

        if (!geneTabs.getChildren().isEmpty()) {
            ((ToggleButton) geneTabs.getChildren().get(0)).setSelected(true);
        }
    }

    private void runSearch() {
        String gene = currentGene();
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
                    ? result.diplotypes().size() + " of " + result.total() + " — narrow the filter"
                    : result.total() + " matching");
        });
        task.setOnFailed(event -> onError.accept(message(task)));
        run(task);
    }

    private void choose(PgxReport.DiplotypeOption option) {
        String gene = currentGene();
        if (gene == null || option == null) {
            return;
        }
        selected.put(gene, option.diplotype());

        Label value = values.get(gene);
        if (value != null) {
            value.setText(option.diplotype());
            value.getStyleClass().remove("unset");
        }
        onChange.run();
    }

    private void clearAll() {
        selected.clear();
        for (Label value : values.values()) {
            value.setText("—");
            if (!value.getStyleClass().contains("unset")) {
                value.getStyleClass().add("unset");
            }
        }
        matches.getSelectionModel().clearSelection();
        onChange.run();
    }

    private String currentGene() {
        ToggleButton tab = (ToggleButton) geneGroup.getSelectedToggle();
        return tab == null ? null : (String) tab.getUserData();
    }

    // ------------------------------------------------------------------

    /** Diplotype left, phenotype right, so the star alleles stay in a column. */
    private static class OptionCell extends ListCell<PgxReport.DiplotypeOption> {

        private final Label diplotype = new Label();
        private final Label phenotype = new Label();
        private final HBox box = new HBox(10, diplotype, phenotype);

        OptionCell() {
            diplotype.getStyleClass().add("diplotype");
            diplotype.setMinWidth(76);
            phenotype.getStyleClass().add("phenotype");
            box.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(PgxReport.DiplotypeOption item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            diplotype.setText(item.diplotype());
            phenotype.setText(item.phenotype() == null ? "—" : item.phenotype());
            setGraphic(box);
        }
    }

    private static Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
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

    private static String message(Task<?> task) {
        Throwable error = task.getException();
        return error == null ? "Unknown error." : error.getMessage();
    }
}
