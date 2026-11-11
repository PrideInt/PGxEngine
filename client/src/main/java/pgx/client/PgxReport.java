package pgx.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Java mirror of the engine's report contract (pgx/models.py, schema 1.1).
 *
 * These are the only shapes the interface knows about. Change pgx/models.py and
 * change this file in the same commit.
 *
 * ignoreUnknown is deliberate: the engine may add fields without breaking this
 * build, so the two halves can move at different speeds. A field REMOVED or
 * renamed is what the schema check below is for.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PgxReport(
    @JsonProperty("engine_version") String engineVersion,
    @JsonProperty("schema_version") String schemaVersion,
    Sources sources,
    List<GeneResult> genes,
    List<DrugRecommendation> drugs,
    List<String> warnings,
    String disclaimer,
    String sample) {

    /** The schema this client was written against. */
    public static final String SUPPORTED_SCHEMA = "1.1";

    /** Guards against a silently mismatched engine. Show a banner rather than
     *  rendering a report you may be misreading. */
    public boolean schemaSupported() {
        return SUPPORTED_SCHEMA.equals(schemaVersion);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sources(
        @JsonProperty("cpic_db") String cpicDb,
        String pharmvar,
        @JsonProperty("retrieved_at") String retrievedAt) 
        
        {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GeneResult(
        String gene,
        String diplotype,
        String phenotype,
        @JsonProperty("activity_score") String activityScore,
        @JsonProperty("ehr_priority") String ehrPriority,
        @JsonProperty("consultation_text") String consultationText,
        @JsonProperty("allele_functions") List<String> alleleFunctions,
        @JsonProperty("lookup_key") Map<String, String> lookupKey,
        String error) {

        /** False when the engine could not resolve this diplotype. Render these
         *  distinctly; never as an ordinary result. */
        public boolean resolved() {
            return error == null && phenotype != null;
        }

        /** CPIC publishes a priority band for only some diplotypes, and for most
         *  CYP2D6 ones it publishes none. Absence is a real state, not a gap. */
        public boolean hasPriority() {
            return ehrPriority != null && !ehrPriority.isBlank();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DrugRecommendation(
        String drug,
        List<String> genes,
        String classification,
        String recommendation,
        String implications,
        String comments,
        String population,
        String source,
        @JsonProperty("cpic_level") String cpicLevel,
        @JsonProperty("guideline_url") String guidelineUrl) {
        
        /**
         * True where the guideline declines to advise.
         *
         * This is an answer, not a blank: for 52% of genotyped samples CPIC
         * returns exactly this for venlafaxine.
         */
        public boolean isAbstention() {
            return "No Recommendation".equalsIgnoreCase(classification);
        }
    }

    /** Request body for POST /report. Null means "unset": let the engine's own
     *  default stand rather than sending a null it will reject. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(Map<String, String> diplotypes, List<String> drugs, @JsonProperty("specific_only") Boolean specificOnly) {
        public Request(Map<String, String> diplotypes, List<String> drugs) {
            this(diplotypes, drugs, null);
        }
    }

    /** One row of GET /drugs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DrugSummary(
        String drug,
        List<String> genes,
        @JsonProperty("cpic_level") String cpicLevel,
        @JsonProperty("recommendation_count") int recommendationCount,
        boolean answerable)

        {}

    /** One row of GET /genes/{gene}/diplotypes. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DiplotypeOption(
        String diplotype, 
        String phenotype, 
        @JsonProperty("activity_score") String activityScore,
        @JsonProperty("ehr_priority") String ehrPriority) 
        
        {}

    /** The body of GET /genes/{gene}/diplotypes: a page, plus the full count. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DiplotypeSearch(String gene, String query, int total, int limit, List<DiplotypeOption> diplotypes) {
        public boolean truncated() {
            return diplotypes != null && total > diplotypes.size();
        }
    }
}
