package pgx.client;

import java.util.List;
import java.util.Map;

/**
 * The boundary between the interface and the engine.
 *
 * The UI depends on THIS and never on a transport. That is what lets the whole
 * interface be built and demonstrated against {@link StubPgxEngine} with no
 * Python process running.
 */
public interface PgxEngine {

    /** @throws PgxException on transport failure or a non-2xx response. */
    PgxReport report(Map<String, String> diplotypes, List<String> drugs) throws PgxException;

    default PgxReport report(Map<String, String> diplotypes) throws PgxException {
        return report(diplotypes, null);
    }

    /** Genes this knowledge base can resolve. Populate pickers from this, never
     *  from a hardcoded list: the build decides what is answerable. */
    List<String> availableGenes() throws PgxException;

    /**
     * Diplotypes for one gene, narrowed by a substring.
     *
     * CYP2D6 alone holds 16,836, so a caller must never ask for a whole gene.
     * The result reports the full match count alongside the page returned.
     */
    PgxReport.DiplotypeSearch searchDiplotypes(String gene, String query, int limit)
            throws PgxException;

    /** Drugs this build can actually answer for, with their genes and evidence
     *  level. Pass true to also receive the ones it cannot, flagged. */
    List<PgxReport.DrugSummary> drugs(boolean includeUnanswerable) throws PgxException;

    default List<PgxReport.DrugSummary> drugs() throws PgxException {
        return drugs(false);
    }

    /** Cheap liveness check for a status indicator. Never throws. */
    boolean healthy();

    /** Checked, so a caller is forced to decide what the interface does on failure. */
    class PgxException extends Exception {
        public PgxException(String message) {
            super(message);
        }
        public PgxException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
