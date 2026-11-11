package pgx.client.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;

import pgx.client.PgxReport;

import java.util.function.Consumer;

/**
 * Renders one report: the genes, then the drugs, then where it all came from.
 *
 * Every drug row opens to show WHY it says what it says, which is the point of
 * the interface. Two states are drawn deliberately rather than left blank: a
 * gene the engine could not resolve, and a drug the guideline declines to
 * advise on. Both are answers.
 */
public class ReportView extends VBox {

    private static final String MONO = "monospace";

    private static final String INK = "#f2f3f5";
    private static final String SOFT = "#c3c8d0";
    private static final String MUTED = "#9aa1ab";
    private static final String LINE = "#343941";
    private static final String CARD = "#272b31";
    private static final String YELLOW = "#f2cf4b";
    private static final String BLUE = "#4f9bd9";
    private static final String PINK = "#ef5680";

    private final Consumer<String> onOpenLink;

    public ReportView(Consumer<String> onOpenLink) {
        this.onOpenLink = onOpenLink;
        setSpacing(18);
        setPadding(new Insets(20));
        showMessage("Add a gene and diplotype, then run the report.");
    }

    public void showMessage(String message) {
        getChildren().setAll(muted(message));
    }

    public void show(PgxReport report) {
        getChildren().clear();

        if (!report.schemaSupported()) {
            getChildren().add(banner(
                    "This engine speaks schema " + report.schemaVersion()
                            + "; this client expects " + PgxReport.SUPPORTED_SCHEMA
                            + ". The report below may be read wrongly.", PINK));
        }

        if (report.warnings() != null) {
            for (String warning : report.warnings()) {
                getChildren().add(banner(warning, YELLOW));
            }
        }

        getChildren().add(heading("GENES"));
        if (report.genes() == null || report.genes().isEmpty()) {
            getChildren().add(muted("No genes were submitted."));
        } else {
            for (PgxReport.GeneResult gene : report.genes()) {
                getChildren().add(geneCard(gene));
            }
        }

        getChildren().add(heading("DRUGS"));
        if (report.drugs() == null || report.drugs().isEmpty()) {
            getChildren().add(muted(
                    "No drug reached a recommendation. Every gene it would have "
                            + "needed was either untyped or unresolved."));
        } else {
            for (PgxReport.DrugRecommendation drug : report.drugs()) {
                getChildren().add(drugRow(drug));
            }
            getChildren().add(muted(
                    "Colour marks CPIC's strength of recommendation, not severity. "
                            + "An outlined badge means the guideline declines to advise."));
        }

        getChildren().add(heading("PROVENANCE"));
        getChildren().add(provenance(report));
    }

    private Region geneCard(PgxReport.GeneResult gene) {
        VBox box = card();

        if (!gene.resolved()) {
            box.setStyle(box.getStyle() + "-fx-border-color: " + PINK + ";");
            box.getChildren().addAll(
                    title(gene.gene() + "  " + nullSafe(gene.diplotype())),
                    body(gene.error() == null ? "Not resolved." : gene.error(), PINK));
            return box;
        }

        box.getChildren().addAll(
                title(gene.gene() + "  " + gene.diplotype()),
                body(gene.phenotype(), INK));

        if (gene.activityScore() != null) {
            box.getChildren().add(field("activity score", gene.activityScore()));
        }
        if (gene.alleleFunctions() != null && !gene.alleleFunctions().isEmpty()) {
            box.getChildren().add(field(
                    "allele function", String.join("  ·  ", gene.alleleFunctions())));
        }

        // CPIC publishes no band for most CYP2D6 diplotypes. Say so rather than
        // leaving a gap the reader has to interpret.
        box.getChildren().add(gene.hasPriority()
                ? field("EHR priority", gene.ehrPriority())
                : field("EHR priority", "none published by CPIC for this diplotype"));

        if (gene.consultationText() != null && !gene.consultationText().isBlank()) {
            box.getChildren().add(wrapped(gene.consultationText(), SOFT, 12));
        }
        return box;
    }

    private Region drugRow(PgxReport.DrugRecommendation drug) {
        VBox detail = new VBox(8);
        detail.setPadding(new Insets(4, 2, 6, 2));

        detail.getChildren().add(wrapped(
                nullSafe(drug.recommendation()), INK, 13));

        if (drug.implications() != null && !drug.implications().isBlank()) {
            detail.getChildren().add(field("implications", null));
            detail.getChildren().add(wrapped(drug.implications(), SOFT, 12));
        }
        if (drug.comments() != null && !drug.comments().isBlank()) {
            detail.getChildren().add(field("comments", null));
            detail.getChildren().add(wrapped(drug.comments(), SOFT, 12));
        }

        detail.getChildren().add(field("decided by",
                drug.genes() == null || drug.genes().isEmpty()
                        ? "no gene" : String.join(" + ", drug.genes())));
        detail.getChildren().add(field("source",
                nullSafe(drug.source()) + "  ·  evidence level " + nullSafe(drug.cpicLevel())
                        + "  ·  " + nullSafe(drug.population())));

        if (drug.guidelineUrl() != null && !drug.guidelineUrl().isBlank()) {
            Hyperlink link = new Hyperlink(drug.guidelineUrl());
            link.setFont(Font.font(MONO, 12));
            link.setStyle("-fx-text-fill: " + BLUE + ";");
            link.setOnAction(event -> onOpenLink.accept(drug.guidelineUrl()));
            detail.getChildren().add(link);
        }

        TitledPane pane = new TitledPane();
        pane.setGraphic(drugHeader(drug));
        pane.setContent(detail);
        pane.setExpanded(false);
        pane.setAnimated(false);
        pane.setStyle("-fx-font-family: '" + MONO + "';");
        return pane;
    }

    private Region drugHeader(PgxReport.DrugRecommendation drug) {
        Label name = new Label(nullSafe(drug.drug()));
        name.setFont(Font.font(MONO, 14));
        name.setStyle("-fx-text-fill: " + INK + ";");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        spacer.setMinWidth(16);

        HBox header = new HBox(10, name, spacer, badge(drug));
        header.setAlignment(Pos.CENTER_LEFT);
        header.setMinWidth(560);
        return header;
    }

    private Label badge(PgxReport.DrugRecommendation drug) {
        String text = nullSafe(drug.classification());
        Label label = new Label(text);
        label.setFont(Font.font(MONO, 11));
        label.setPadding(new Insets(2, 9, 2, 9));

        if (drug.isAbstention()) {
            // An abstention is a result, so it is marked, not greyed out of sight.
            label.setStyle(
                    "-fx-text-fill: " + MUTED + ";"
                            + "-fx-border-color: " + MUTED + ";"
                            + "-fx-border-radius: 3; -fx-font-style: italic;");
        } else {
            String colour = switch (text.toLowerCase()) {
                case "strong" -> BLUE;
                case "moderate" -> YELLOW;
                default -> SOFT;
            };
            label.setStyle(
                    "-fx-text-fill: #1b1e22;"
                            + "-fx-background-color: " + colour + ";"
                            + "-fx-background-radius: 3;");
        }
        return label;
    }

    private Region provenance(PgxReport report) {
        VBox box = card();
        PgxReport.Sources sources = report.sources();

        if (sources != null) {
            box.getChildren().addAll(
                    field("CPIC data", nullSafe(sources.cpicDb())),
                    field("PharmVar", nullSafe(sources.pharmvar())),
                    field("retrieved", nullSafe(sources.retrievedAt())));
        }
        box.getChildren().add(field("engine", nullSafe(report.engineVersion())
                + "  ·  schema " + nullSafe(report.schemaVersion())));

        if (report.disclaimer() != null) {
            box.getChildren().add(wrapped(report.disclaimer(), PINK, 12));
        }
        return box;
    }

    private VBox card() {
        VBox box = new VBox(6);
        box.setPadding(new Insets(14, 16, 14, 16));
        box.setStyle(
                "-fx-background-color: " + CARD + ";"
                        + "-fx-border-color: " + LINE + ";"
                        + "-fx-background-radius: 6; -fx-border-radius: 6;");
        return box;
    }

    private Label heading(String text) {
        Label label = new Label("// " + text);
        label.setFont(Font.font(MONO, 11));
        label.setStyle("-fx-text-fill: " + MUTED + ";");
        return label;
    }

    private Label title(String text) {
        Label label = new Label(text);
        label.setFont(Font.font(MONO, 15));
        label.setStyle("-fx-text-fill: " + INK + ";");
        return label;
    }

    private Label body(String text, String colour) {
        Label label = new Label(nullSafe(text));
        label.setFont(Font.font(MONO, 13));
        label.setStyle("-fx-text-fill: " + colour + ";");
        label.setWrapText(true);
        return label;
    }

    private Label field(String name, String value) {
        Label label = new Label(value == null ? name : name + ":  " + value);
        label.setFont(Font.font(MONO, 12));
        label.setStyle("-fx-text-fill: " + MUTED + ";");
        label.setWrapText(true);
        return label;
    }

    private Label wrapped(String text, String colour, int size) {
        Label label = new Label(nullSafe(text));
        label.setFont(Font.font(MONO, size));
        label.setStyle("-fx-text-fill: " + colour + ";");
        label.setWrapText(true);
        label.setMaxWidth(640);
        return label;
    }

    private Label muted(String text) {
        return wrapped(text, MUTED, 12);
    }

    private Region banner(String text, String colour) {
        Label label = wrapped(text, colour, 12);
        label.setPadding(new Insets(10, 14, 10, 14));
        label.setStyle(label.getStyle()
                + "-fx-border-color: " + colour + ";"
                + "-fx-border-radius: 4;");
        return label;
    }

    private static String nullSafe(String value) {
        return value == null ? "—" : value;
    }
}
