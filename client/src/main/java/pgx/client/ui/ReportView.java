package pgx.client.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import pgx.client.PgxReport;

import java.util.function.Consumer;

/**
 * Renders one report: the genes, then the drugs, then where it all came from.
 *
 * A drug row shows its recommendation before you click it. Collapsing the
 * answer behind a disclosure arrow made the first thing you saw after running
 * a report a row of thin bars; now the first sentence is on the row and the
 * arrow opens the reasoning — the implications, the comments, which genes
 * decided it, and the guideline it came from.
 *
 * Two states are drawn deliberately rather than left blank: a gene the engine
 * could not resolve, and a drug the guideline declines to advise on. Both are
 * answers, and the second one is the project's whole argument.
 */
public class ReportView extends VBox {

    private static final double PROSE_WIDTH = 760;
    private static final int SUMMARY_LIMIT = 190;

    private final Consumer<String> onOpenLink;

    public ReportView(Consumer<String> onOpenLink) {
        this.onOpenLink = onOpenLink;
        getStyleClass().add("report");
        showEmptyState();
    }

    // ------------------------------------------------------------------

    public void showEmptyState() {
        VBox box = card();
        box.getChildren().addAll(
                styled(new Label("No report yet"), "empty-title"),
                wrap(styled(new Label(
                        "Pick a diplotype for at least one gene on the left, "
                                + "then run the report."), "empty-body")),
                rule(),
                wrap(styled(new Label(
                        "What you get: CPIC's own recommendation for every drug your "
                                + "genotype reaches, with the guideline text and a link to it.\n\n"
                                + "What you do not get: a combined score, or drugs ranked "
                                + "best to worst. The engine reports what the guidelines say "
                                + "and stops there."), "empty-aside")));
        getChildren().setAll(box);
    }

    public void showMessage(String message) {
        getChildren().setAll(wrap(styled(new Label(message), "empty-body")));
    }

    public void show(PgxReport report) {
        getChildren().clear();

        if (!report.schemaSupported()) {
            getChildren().add(banner(
                    "This engine speaks schema " + report.schemaVersion()
                            + "; this client expects " + PgxReport.SUPPORTED_SCHEMA
                            + ". The report below may be read wrongly.", "error"));
        }
        if (report.warnings() != null) {
            for (String warning : report.warnings()) {
                getChildren().add(banner(warning, "warn"));
            }
        }

        getChildren().add(heading("GENES"));
        if (report.genes() == null || report.genes().isEmpty()) {
            getChildren().add(note("No genes were submitted."));
        } else {
            for (PgxReport.GeneResult gene : report.genes()) {
                getChildren().add(geneCard(gene));
            }
        }

        getChildren().add(heading("DRUGS"));
        if (report.drugs() == null || report.drugs().isEmpty()) {
            getChildren().add(note(
                    "No drug reached a recommendation. Every gene it would have "
                            + "needed was either untyped or unresolved."));
        } else {
            boolean abstains = false;
            for (PgxReport.DrugRecommendation drug : report.drugs()) {
                getChildren().add(drugRow(drug));
                abstains |= drug.isAbstention();
            }
            // The legend explains the outlined badge, so it earns its line only
            // when there is an outlined badge on screen.
            if (abstains) {
                getChildren().add(wrap(styled(new Label(
                        "An outlined badge means the guideline declines to advise. "
                                + "Colour marks CPIC's strength of recommendation, not severity."),
                        "legend")));
            }
        }

        getChildren().add(heading("PROVENANCE"));
        getChildren().add(provenance(report));
    }

    // ------------------------------------------------------------------

    private Region geneCard(PgxReport.GeneResult gene) {
        VBox box = card();

        if (!gene.resolved()) {
            box.getStyleClass().add("unresolved");
            Label subtitle = styled(new Label(
                    gene.error() == null ? "Not resolved." : gene.error()), "card-subtitle");
            subtitle.getStyleClass().add("unresolved");
            box.getChildren().addAll(
                    styled(new Label(gene.gene() + "   " + nullSafe(gene.diplotype())), "card-title"),
                    wrap(subtitle));
            return box;
        }

        box.getChildren().addAll(
                styled(new Label(gene.gene() + "   " + gene.diplotype()), "card-title"),
                styled(new Label(nullSafe(gene.phenotype())), "card-subtitle"),
                rule());

        GridPane fields = fields();
        if (gene.activityScore() != null) {
            field(fields, "activity score", gene.activityScore(), false);
        }
        if (gene.alleleFunctions() != null && !gene.alleleFunctions().isEmpty()) {
            field(fields, "allele function",
                    String.join("  ·  ", gene.alleleFunctions()), false);
        }
        // CPIC publishes no band for most CYP2D6 diplotypes. Say so rather than
        // leaving a gap the reader has to interpret.
        if (gene.hasPriority()) {
            field(fields, "EHR priority", gene.ehrPriority(), false);
        } else {
            field(fields, "EHR priority", "none published by CPIC for this diplotype", true);
        }
        // Worth showing: for CYP2D6 this is the activity-score bucket the
        // recommendation was matched on, not the diplotype itself.
        if (gene.lookupKey() != null && !gene.lookupKey().isEmpty()) {
            field(fields, "lookup key", String.join("  ·  ",
                    gene.lookupKey().entrySet().stream()
                            .map(entry -> entry.getKey() + " = " + entry.getValue())
                            .toList()), false);
        }
        box.getChildren().add(fields);

        if (gene.consultationText() != null && !gene.consultationText().isBlank()) {
            box.getChildren().addAll(
                    rule(),
                    wrap(styled(new Label(gene.consultationText()), "consultation")));
        }
        return box;
    }

    private Region drugRow(PgxReport.DrugRecommendation drug) {
        Label twisty = styled(new Label("▸"), "drug-twisty");
        Label name = styled(new Label(nullSafe(drug.drug())), "drug-name");

        HBox top = new HBox(10, twisty, name, grow(), badge(drug));
        top.setAlignment(Pos.CENTER_LEFT);

        Label summary = wrap(styled(new Label(summarise(drug.recommendation())), "drug-summary"));
        Label meta = styled(new Label(meta(drug)), "drug-meta");

        VBox text = new VBox(5, summary, meta);
        VBox.setMargin(text, new Insets(0, 0, 0, 24));

        VBox head = new VBox(7, top, text);
        head.getStyleClass().add("drug-head");

        VBox detail = detail(drug);
        detail.setVisible(false);
        detail.setManaged(false);

        VBox row = new VBox(head, detail);
        row.getStyleClass().add("drug-row");

        head.setOnMouseClicked(event -> {
            boolean open = !detail.isManaged();
            detail.setManaged(open);
            detail.setVisible(open);
            twisty.setText(open ? "▾" : "▸");
            // The summary is a truncation of the text now shown in full below it.
            summary.setManaged(!open);
            summary.setVisible(!open);
        });
        return row;
    }

    private VBox detail(PgxReport.DrugRecommendation drug) {
        VBox box = new VBox();
        box.getStyleClass().add("drug-detail");

        box.getChildren().addAll(
                styled(new Label("RECOMMENDATION"), "detail-label"),
                wrap(styled(new Label(nullSafe(drug.recommendation())), "detail-body")));

        if (drug.implications() != null && !drug.implications().isBlank()) {
            box.getChildren().addAll(
                    styled(new Label("IMPLICATIONS"), "detail-label"),
                    wrap(styled(new Label(drug.implications()), "detail-body")));
        }
        if (drug.comments() != null && !drug.comments().isBlank()) {
            box.getChildren().addAll(
                    styled(new Label("COMMENTS"), "detail-label"),
                    wrap(styled(new Label(drug.comments()), "detail-body")));
        }

        // The genes and the evidence level are on the row's meta line, which
        // stays visible when this opens. Only what is not already up there.
        GridPane fields = fields();
        field(fields, "population", nullSafe(drug.population()), false);
        field(fields, "source", nullSafe(drug.source()), false);
        box.getChildren().add(fields);

        if (drug.guidelineUrl() != null && !drug.guidelineUrl().isBlank()) {
            Hyperlink link = new Hyperlink(drug.guidelineUrl());
            link.getStyleClass().add("link");
            link.setOnAction(event -> onOpenLink.accept(drug.guidelineUrl()));
            box.getChildren().add(link);
        }
        return box;
    }

    private Label badge(PgxReport.DrugRecommendation drug) {
        String text = nullSafe(drug.classification());
        Label label = styled(new Label(text), "badge");

        if (drug.isAbstention()) {
            label.getStyleClass().add("abstain");
        } else {
            label.getStyleClass().add(switch (text.toLowerCase()) {
                case "strong" -> "strong";
                case "moderate" -> "moderate";
                default -> "optional";
            });
        }
        return label;
    }

    private Region provenance(PgxReport report) {
        VBox box = card();
        PgxReport.Sources sources = report.sources();

        GridPane fields = fields();
        if (sources != null) {
            field(fields, "CPIC data", nullSafe(sources.cpicDb()), false);
            field(fields, "PharmVar", nullSafe(sources.pharmvar()), false);
            field(fields, "retrieved", nullSafe(sources.retrievedAt()), false);
        }
        field(fields, "engine", nullSafe(report.engineVersion())
                + "   ·   schema " + nullSafe(report.schemaVersion()), false);
        box.getChildren().add(fields);

        if (report.disclaimer() != null) {
            box.getChildren().addAll(
                    rule(),
                    wrap(styled(new Label(report.disclaimer()), "disclaimer")));
        }
        return box;
    }

    // ------------------------------------------------------------------

    /** First sentence, or a hard cut — enough to tell the rows apart. */
    private static String summarise(String text) {
        if (text == null || text.isBlank()) {
            return "—";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        int stop = flat.indexOf(". ");
        if (stop > 40 && stop < SUMMARY_LIMIT) {
            return flat.substring(0, stop + 1);
        }
        return flat.length() <= SUMMARY_LIMIT
                ? flat
                : flat.substring(0, SUMMARY_LIMIT).trim() + "…";
    }

    private static String meta(PgxReport.DrugRecommendation drug) {
        String genes = drug.genes() == null || drug.genes().isEmpty()
                ? "no gene" : String.join(" + ", drug.genes());
        return "decided by " + genes + "   ·   level " + nullSafe(drug.cpicLevel());
    }

    private static GridPane fields() {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(5);

        ColumnConstraints names = new ColumnConstraints();
        names.setMinWidth(104);
        names.setPrefWidth(104);

        ColumnConstraints values = new ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);

        grid.getColumnConstraints().addAll(names, values);
        return grid;
    }

    private static void field(GridPane grid, String name, String value, boolean absent) {
        Label key = styled(new Label(name), "field-name");
        Label shown = wrap(styled(new Label(nullSafe(value)), "field-value"));
        if (absent) {
            shown.getStyleClass().add("absent");
        }
        grid.addRow(grid.getRowCount(), key, shown);
    }

    private static VBox card() {
        VBox box = new VBox();
        box.getStyleClass().add("card");
        return box;
    }

    private static Label heading(String text) {
        return styled(new Label("// " + text), "report-heading");
    }

    private static Label note(String text) {
        return wrap(styled(new Label(text), "empty-body"));
    }

    private static Region banner(String text, String kind) {
        Label label = wrap(styled(new Label(text), "banner"));
        label.getStyleClass().add(kind);
        return label;
    }

    private static Region rule() {
        Region line = new Region();
        line.getStyleClass().add("rule");
        VBox.setMargin(line, new Insets(4, 0, 4, 0));
        return line;
    }

    private static Region grow() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        spacer.setMinWidth(16);
        return spacer;
    }

    private static Label styled(Label label, String styleClass) {
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static Label wrap(Label label) {
        label.setWrapText(true);
        label.setMaxWidth(PROSE_WIDTH);
        return label;
    }

    private static String nullSafe(String value) {
        return value == null ? "—" : value;
    }
}
