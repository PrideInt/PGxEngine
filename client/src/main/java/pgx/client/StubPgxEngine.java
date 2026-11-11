package pgx.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Canned responses, so the whole interface can be built, run and demonstrated
 * with no Python process alive.
 *
 *     PgxEngine engine = new StubPgxEngine();   // swap for HttpPgxEngine later
 *
 * The payload deliberately carries the awkward cases, because those are the
 * ones an interface forgets until a demo exposes them: a gene that did not
 * resolve, a drug the guideline declines to advise on, and a diplotype CPIC
 * publishes no priority band for.
 */
public class StubPgxEngine implements PgxEngine {

    private static final String REPORT = """
        {
          "engine_version": "0.1.0",
          "schema_version": "1.1",
          "sources": {
            "cpic_db": "STUB",
            "pharmvar": "via CPIC (API import)",
            "retrieved_at": "1970-01-01T00:00:00+00:00"
          },
          "genes": [
            {
              "gene": "CYP2C19", "diplotype": "*2/*2",
              "phenotype": "Poor Metabolizer", "activity_score": null,
              "ehr_priority": "Abnormal/Priority/High Risk",
              "consultation_text": "Stub consultation text for layout only.",
              "allele_functions": ["No function", "No function"],
              "lookup_key": {"CYP2C19": "Poor Metabolizer"}, "error": null
            },
            {
              "gene": "CYP2D6", "diplotype": "*1/*4",
              "phenotype": "Intermediate Metabolizer", "activity_score": "1.0",
              "ehr_priority": null,
              "consultation_text": null,
              "allele_functions": ["Normal function", "No function"],
              "lookup_key": {"CYP2D6": "1.0"}, "error": null
            },
            {
              "gene": "NUDT15", "diplotype": "*1/*3",
              "phenotype": null, "activity_score": null,
              "ehr_priority": null, "consultation_text": null,
              "allele_functions": [], "lookup_key": {},
              "error": "Gene 'NUDT15' is not present in this knowledge base."
            }
          ],
          "drugs": [
            {
              "drug": "escitalopram", "genes": ["CYP2C19"],
              "classification": "Strong",
              "recommendation": "Consider an alternative, or a 50% maintenance dose reduction.",
              "implications": "Stub implication text for layout only.",
              "comments": null, "population": "general",
              "source": "CPIC", "cpic_level": "A",
              "guideline_url": "https://example.invalid/guideline"
            },
            {
              "drug": "paroxetine", "genes": ["CYP2D6"],
              "classification": "Optional",
              "recommendation": "Consider a lower starting dose and slower titration.",
              "implications": "Stub implication text for layout only.",
              "comments": null, "population": "general",
              "source": "CPIC", "cpic_level": "A",
              "guideline_url": "https://example.invalid/guideline"
            },
            {
              "drug": "venlafaxine", "genes": ["CYP2D6"],
              "classification": "No Recommendation",
              "recommendation": "No action recommended. Minimal evidence of clinical impact.",
              "implications": "Stub implication text for layout only.",
              "comments": null, "population": "general",
              "source": "CPIC", "cpic_level": "B",
              "guideline_url": "https://example.invalid/guideline"
            }
          ],
          "warnings": ["NUDT15: Gene 'NUDT15' is not present in this knowledge base."],
          "disclaimer": "Educational use only. Not clinical decision support. Not validated for patient care.",
          "sample": null
        }
        """;

    /**
     * A few diplotypes per gene, so the stub answers for the gene asked for
     * instead of returning one canned list. The totals are the real counts
     * from a CPIC build, which keeps the "showing N of M" path honest.
     */
    private static final Map<String, List<PgxReport.DiplotypeOption>> BY_GENE = Map.of(
        "CYP2C19", List.of(
            new PgxReport.DiplotypeOption("*1/*1", "Normal Metabolizer", null,
                "Normal/Routine/Low Risk"),
            new PgxReport.DiplotypeOption("*1/*2", "Intermediate Metabolizer", null,
                "Abnormal/Priority/High Risk"),
            new PgxReport.DiplotypeOption("*1/*17", "Rapid Metabolizer", null,
                "Normal/Routine/Low Risk"),
            new PgxReport.DiplotypeOption("*2/*2", "Poor Metabolizer", null,
                "Abnormal/Priority/High Risk")),
        "CYP2D6", List.of(
            new PgxReport.DiplotypeOption("*1/*1", "Normal Metabolizer", "2.0", null),
            new PgxReport.DiplotypeOption("*1/*4", "Intermediate Metabolizer", "1.0", null),
            new PgxReport.DiplotypeOption("*4/*4", "Poor Metabolizer", "0.0",
                "Abnormal/Priority/High Risk"),
            new PgxReport.DiplotypeOption("*4/*5", "Poor Metabolizer", "0.0",
                "Abnormal/Priority/High Risk")),
        "CYP2B6", List.of(
            new PgxReport.DiplotypeOption("*1/*1", "Normal Metabolizer", null,
                "Normal/Routine/Low Risk"),
            new PgxReport.DiplotypeOption("*1/*6", "Intermediate Metabolizer", null, null),
            new PgxReport.DiplotypeOption("*6/*6", "Poor Metabolizer", null,
                "Abnormal/Priority/High Risk"))
    );

    private static final Map<String, Integer> TOTALS = Map.of("CYP2C19", 666, "CYP2D6", 16836, "CYP2B6", 1176);

    private static final String DRUGS = """
        [
          {"drug": "escitalopram", "genes": ["CYP2C19"], "cpic_level": "A",
           "recommendation_count": 8, "answerable": true},
          {"drug": "paroxetine", "genes": ["CYP2D6"], "cpic_level": "A",
           "recommendation_count": 22, "answerable": true},
          {"drug": "sertraline", "genes": ["CYP2B6", "CYP2C19"], "cpic_level": "A",
           "recommendation_count": 62, "answerable": true},
          {"drug": "venlafaxine", "genes": ["CYP2D6"], "cpic_level": "B",
           "recommendation_count": 22, "answerable": true},
          {"drug": "warfarin", "genes": ["CYP2C9"], "cpic_level": "A",
           "recommendation_count": 12, "answerable": false}
        ]
        """;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public PgxReport report(Map<String, String> diplotypes, List<String> drugs) throws PgxException {
        try {
            return mapper.readValue(REPORT, PgxReport.class);
        } catch (Exception e) {
            throw new PgxException("The stub payload failed to parse.", e);
        }
    }

    @Override
    public List<String> availableGenes() {
        return List.of("CYP2B6", "CYP2C19", "CYP2D6");
    }

    @Override
    public PgxReport.DiplotypeSearch searchDiplotypes(String gene, String query, int limit) {
        String key = gene == null ? "" : gene.trim().toUpperCase();
        List<PgxReport.DiplotypeOption> all = BY_GENE.getOrDefault(key, List.of());
        String filter = query == null ? "" : query.trim();

        List<PgxReport.DiplotypeOption> matching = all.stream()
                .filter(option -> filter.isEmpty() || option.diplotype().contains(filter))
                .limit(Math.max(1, limit))
                .toList();

        // Scale the real total by the filter so the truncation notice still shows.
        int total = filter.isEmpty() ? TOTALS.getOrDefault(key, matching.size()) : matching.size();

        return new PgxReport.DiplotypeSearch(key, filter.isEmpty() ? null : filter, Math.max(total, matching.size()), limit, matching);
    }

    @Override
    public List<PgxReport.DrugSummary> drugs(boolean includeUnanswerable)
            throws PgxException {
        try {
            List<PgxReport.DrugSummary> all = mapper.readValue(DRUGS, new TypeReference<List<PgxReport.DrugSummary>>() {});

            if (includeUnanswerable) {
                return all;
            }
            return all.stream().filter(PgxReport.DrugSummary::answerable).toList();

        } catch (Exception e) {
            throw new PgxException("The stub payload failed to parse.", e);
        }
    }

    @Override
    public boolean healthy() {
        return true;
    }
}
