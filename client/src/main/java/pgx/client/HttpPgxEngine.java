package pgx.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Talks to the FastAPI engine over localhost.
 *
 * Two dependencies only: the JDK's own HttpClient and Jackson for JSON.
 */
public class HttpPgxEngine implements PgxEngine {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpPgxEngine() {
        this("http://127.0.0.1:8000");
    }

    public HttpPgxEngine(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;

        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public PgxReport report(Map<String, String> diplotypes, List<String> drugs) throws PgxException {
        if (diplotypes == null || diplotypes.isEmpty()) {
            throw new PgxException("At least one diplotype is required.");
        }

        String body;
        try {
            body = mapper.writeValueAsString(new PgxReport.Request(diplotypes, drugs));
        } catch (Exception e) {
            throw new PgxException("Could not encode the request.", e);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/report"))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        String response = send(request, "/report");
        PgxReport parsed = parse(response, PgxReport.class, "/report");

        if (!parsed.schemaSupported()) {
            throw new PgxException(
                    "The engine speaks schema " + parsed.schemaVersion()
                            + " but this client expects " + PgxReport.SUPPORTED_SCHEMA
                            + ". Rebuild one side or the other.");
        }
        return parsed;
    }

    @Override
    public List<String> availableGenes() throws PgxException {
        JsonNode root = tree(get("/genes"), "/genes");
        List<String> genes = new ArrayList<>();

        root.path("genes").forEach(node -> genes.add(node.path("gene").asText()));

        return genes;
    }

    @Override
    public PgxReport.DiplotypeSearch searchDiplotypes(String gene, String query, int limit) throws PgxException {
        StringBuilder path = new StringBuilder("/genes/")
                .append(encode(gene))
                .append("/diplotypes?limit=")
                .append(limit);

        if (query != null && !query.isBlank()) {
            path.append("&q=").append(encode(query));
        }
        return parse(get(path.toString()), PgxReport.DiplotypeSearch.class, path.toString());
    }

    @Override
    public List<PgxReport.DrugSummary> drugs(boolean includeUnanswerable) throws PgxException {
        String path = "/drugs" + (includeUnanswerable ? "?all=true" : "");
        JsonNode root = tree(get(path), path);

        try {
            return mapper.convertValue(
                    root.path("drugs"),
                    new TypeReference<List<PgxReport.DrugSummary>>() {});
        } catch (Exception e) {
            throw new PgxException("The engine returned an unreadable drug list.", e);
        }
    }

    @Override
    public boolean healthy() {
        try {
            return "ok".equals(tree(get("/health"), "/health").path("status").asText());
        } catch (Exception e) {
            return false;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String get(String path) throws PgxException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(TIMEOUT)
                .GET()
                .build();
        return send(request, path);
    }

    private String send(HttpRequest request, String path) throws PgxException {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            int status = response.statusCode();

            if (status == 404) {
                throw new PgxException("The engine has nothing at " + path + ".");
            }
            if (status == 400 || status == 422) {
                throw new PgxException(
                        "The engine rejected the request: " + detail(response.body()));
            }
            if (status / 100 != 2) {
                throw new PgxException(path + " returned HTTP " + status + ".");
            }
            return response.body();

        } catch (PgxException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new PgxException("Cannot reach the PGx engine at " + baseUrl + ". Is uvicorn running?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PgxException("The request was interrupted.", e);
        }
    }

    private <T> T parse(String body, Class<T> type, String path) throws PgxException {
        try {
            return mapper.readValue(body, type);
        } catch (Exception e) {
            throw new PgxException("Could not read the response from " + path + ".", e);
        }
    }

    private JsonNode tree(String body, String path) throws PgxException {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new PgxException("Could not read the response from " + path + ".", e);
        }
    }

    /** FastAPI puts validation errors under "detail"; fall back to the raw body. */
    private String detail(String body) {
        try {
            JsonNode node = mapper.readTree(body).path("detail");
            return node.isMissingNode() ? body : node.toString();
        } catch (Exception e) {
            return body;
        }
    }
}
