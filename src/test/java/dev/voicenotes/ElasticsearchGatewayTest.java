package dev.voicenotes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class ElasticsearchGatewayTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> body = new AtomicReference<>();
    private HttpServer server;
    private ElasticsearchGateway gateway;
    private String response = "{}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (!exchange.getRequestMethod().equals("GET")) body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        gateway = new ElasticsearchGateway(json, "http://127.0.0.1:" + server.getAddress().getPort(), "");
    }

    @AfterEach
    void stopServer() { server.stop(0); }

    @Test
    void writesSpeakerNamesToTheSameDocumentWithoutChangingOffsets() throws Exception {
        gateway.save("note", List.of(new TranscriptSegment(7, "custom-123", "수정된 전사 👍", 1250, 4000, "김민수")));
        String[] lines = body.get().split("\n");
        assertThat(lines).hasSize(2);
        assertThat(json.readTree(lines[0]).path("index").path("_id").asText()).isEqualTo("note-7");
        JsonNode source = json.readTree(lines[1]);
        assertThat(source.path("speaker").asText()).isEqualTo("custom-123");
        assertThat(source.path("speakerName").asText()).isEqualTo("김민수");
        assertThat(source.path("text").asText()).isEqualTo("수정된 전사 👍");
        assertThat(source.path("startMs").asLong()).isEqualTo(1250);
        assertThat(source.path("endMs").asLong()).isEqualTo(4000);
    }

    @Test
    void readsEditedAndLegacySegmentsInSequenceOrder() throws Exception {
        response = """
                {"hits":{"hits":[{"_source":{"sequence":0,"speaker":"1","speakerName":"민수","text":"안녕","startMs":0,"endMs":1000}},
                {"_source":{"sequence":1,"speaker":"2","text":"네","startMs":1000,"endMs":1500}}]}}
                """;
        assertThat(gateway.get("note")).containsExactly(new TranscriptSegment(0, "1", "안녕", 0, 1000, "민수"),
                new TranscriptSegment(1, "2", "네", 1000, 1500));
        assertThat(json.readTree(body.get()).path("sort").get(0).path("sequence").asText()).isEqualTo("asc");
    }

    @Test
    void surfacesPartialBulkFailures() {
        response = "{\"errors\":true}";
        assertThatThrownBy(() -> gateway.save("note", List.of(new TranscriptSegment(0, "1", "내용", 0, 0))))
                .isInstanceOf(IOException.class).hasMessageContaining("저장이 실패");
    }
}
