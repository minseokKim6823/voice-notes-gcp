package dev.voicenotes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ElasticsearchGateway {
    private static final String INDEX = "voice-note-segments";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;
    private final String baseUrl;
    private final String apiKey;
    private volatile boolean indexReady;

    public ElasticsearchGateway(ObjectMapper json,
            @Value("${voice-notes.elasticsearch-url}") String baseUrl,
            @Value("${voice-notes.elasticsearch-api-key}") String apiKey) {
        this.json = json;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
    }

    private JsonNode request(String method, String path, String body, String type, boolean allowNotFound) throws IOException {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofMinutes(2));
            if (!apiKey.isBlank()) builder.header("Authorization", "ApiKey " + apiKey);
            if (type != null) builder.header("Content-Type", type);
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (allowNotFound && response.statusCode() == 404) return null;
            if (response.statusCode() >= 300) throw new IOException("Elasticsearch HTTP " + response.statusCode()
                    + ": " + response.body().substring(0, Math.min(400, response.body().length())));
            return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Elasticsearch 요청이 중단되었습니다.", e);
        } catch (IllegalArgumentException e) {
            throw new IOException("ELASTICSEARCH_URL 설정이 올바르지 않습니다.", e);
        }
    }

    private synchronized void ensureIndex() throws IOException {
        if (indexReady) return;
        if (request("GET", "/" + INDEX, null, null, true) == null) {
            String mapping = json.writeValueAsString(Map.of("mappings", Map.of("properties", Map.of(
                    "noteId", Map.of("type", "keyword"),
                    "sequence", Map.of("type", "integer"),
                    "speaker", Map.of("type", "keyword"),
                    "text", Map.of("type", "text"),
                    "startMs", Map.of("type", "long"),
                    "endMs", Map.of("type", "long")))));
            request("PUT", "/" + INDEX, mapping, "application/json", false);
        }
        indexReady = true;
    }

    public void save(String noteId, List<TranscriptSegment> segments) throws IOException {
        ensureIndex();
        StringBuilder ndjson = new StringBuilder();
        for (TranscriptSegment segment : segments) {
            ndjson.append(json.writeValueAsString(Map.of("index", Map.of("_index", INDEX,
                    "_id", noteId + "-" + segment.sequence())))).append('\n');
            ndjson.append(json.writeValueAsString(Map.of("noteId", noteId,
                    "sequence", segment.sequence(), "speaker", segment.speaker(), "text", segment.text(),
                    "startMs", segment.startMs(), "endMs", segment.endMs()))).append('\n');
        }
        JsonNode result = request("POST", "/_bulk?refresh=wait_for", ndjson.toString(), "application/x-ndjson", false);
        if (result.path("errors").asBoolean()) throw new IOException("전사 내용의 Elasticsearch 저장이 실패했습니다.");
    }

    public List<TranscriptSegment> get(String noteId) throws IOException {
        ensureIndex();
        String query = json.writeValueAsString(Map.of("size", 10000,
                "query", Map.of("term", Map.of("noteId", noteId)),
                "sort", List.of(Map.of("sequence", "asc"))));
        JsonNode result = request("POST", "/" + INDEX + "/_search", query, "application/json", false);
        List<TranscriptSegment> segments = new ArrayList<>();
        for (JsonNode hit : result.path("hits").path("hits")) {
            JsonNode source = hit.path("_source");
            segments.add(new TranscriptSegment(source.path("sequence").asInt(), source.path("speaker").asText(),
                    source.path("text").asText(), source.path("startMs").asLong(), source.path("endMs").asLong()));
        }
        return segments;
    }

    public Set<String> searchNoteIds(String text) throws IOException {
        ensureIndex();
        String query = json.writeValueAsString(Map.of("size", 1000, "_source", List.of("noteId"),
                "query", Map.of("match", Map.of("text", text))));
        JsonNode result = request("POST", "/" + INDEX + "/_search", query, "application/json", false);
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode hit : result.path("hits").path("hits")) ids.add(hit.path("_source").path("noteId").asText());
        return ids;
    }
}
