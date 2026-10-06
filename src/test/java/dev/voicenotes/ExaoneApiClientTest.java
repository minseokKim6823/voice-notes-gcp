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
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExaoneApiClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<JsonNode> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> method = new AtomicReference<>();
    private int status = 200;
    private String response = """
            {"choices":[{"finish_reason":"stop","message":{
              "content":"  회의 요약: 출시일을 확정했습니다.  ","reasoning_content":"internal reasoning"}}]}
            """;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(json.readTree(exchange.getRequestBody()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            method.set(exchange.getRequestMethod());
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
    }

    @AfterEach
    void stopServer() { if (server != null) server.stop(0); }

    private ExaoneApiClient client(String key, boolean template) {
        return new ExaoneApiClient(json, baseUrl, key, "test-exaone", Duration.ofSeconds(5), 4096, template);
    }

    @Test
    void sendsChatCompletionsContractAndReadsFinalContent() throws Exception {
        assertThat(client("test-key", true).complete("화자 1: 금요일에 출시합니다."))
                .isEqualTo("회의 요약: 출시일을 확정했습니다.");
        assertThat(method.get()).isEqualTo("POST");
        assertThat(authorization.get()).isEqualTo("Bearer test-key");
        JsonNode body = requestBody.get();
        assertThat(body.path("model").asText()).isEqualTo("test-exaone");
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(body.path("max_tokens").asInt()).isEqualTo(4096);
        assertThat(body.path("messages").path(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").path(1).path("content").asText()).contains("화자 1:");
        assertThat(body.path("chat_template_kwargs").path("enable_thinking").asBoolean()).isFalse();
    }

    @Test
    void allowsGatewaysWithoutTemplateExtensionOrAuthentication() throws Exception {
        client("", false).complete("요약해주세요");
        assertThat(authorization.get()).isNull();
        assertThat(requestBody.get().has("chat_template_kwargs")).isFalse();
    }

    @Test
    void doesNotExposeRemoteErrorBody() {
        status = 401;
        response = "{\"error\":\"secret-token and private transcript\"}";
        assertThatThrownBy(() -> client("secret-token", true).complete("private transcript"))
                .isInstanceOf(IOException.class).hasMessageContaining("HTTP 401")
                .hasMessageNotContaining("secret-token").hasMessageNotContaining("private transcript");
    }

    @Test
    void rejectsTruncatedSummaries() {
        response = "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}";
        assertThatThrownBy(() -> client("", true).complete("회의"))
                .isInstanceOf(IOException.class).hasMessageContaining("출력 한도");
    }

    @Test
    void rejectsReasoningOnlyResponses() {
        response = "{\"choices\":[{\"message\":{\"reasoning_content\":\"reasoning only\"}}]}";
        assertThatThrownBy(() -> client("", true).complete("회의"))
                .isInstanceOf(IOException.class).hasMessageContaining("요약이 없습니다");
    }

    @Test
    void rejectsInvalidJsonWithoutLeakingBody() {
        response = "private provider error page";
        assertThatThrownBy(() -> client("", true).complete("회의"))
                .isInstanceOf(IOException.class).hasMessageContaining("JSON")
                .hasMessageNotContaining("private");
    }

    @Test
    void missingConfigurationFailsBeforeNetworkRequest() {
        baseUrl = "";
        assertThatThrownBy(() -> client("", true).complete("회의"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EXAONE_BASE_URL");
        assertThat(requestBody.get()).isNull();
    }
}
