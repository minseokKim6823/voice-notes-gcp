package dev.voicenotes;

import dev.voicenotes.domain.*;
import dev.voicenotes.infrastructure.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class WhisperApiClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private String baseUrl;
    private String body;
    private String contentType;
    private String authorization;
    private String method;
    private int status = 200;
    private String response = """
            {"text":"안녕하세요. 회의를 시작합니다.","segments":[
              {"start":0.12,"end":1.25,"text":" 안녕하세요. "},
              {"start":1.5,"end":3.1,"text":"회의를 시작합니다."}]}
            """;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/audio/transcriptions", exchange -> {
            body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            method = exchange.getRequestMethod();
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
    }

    @AfterEach
    void stop() { server.stop(0); }

    private WhisperApiClient client() { return new WhisperApiClient(json, baseUrl, "test-key", Duration.ofSeconds(5)); }

    @Test
    void sendsKoreanWhisperMultipartAndPreservesSegmentTimesWithoutInventingSpeakers() throws Exception {
        assertThat(client().transcribe("audio-bytes".getBytes(StandardCharsets.UTF_8), "audio/webm"))
                .containsExactly(new TranscriptSegment(0, "미확인", "안녕하세요.", 120, 1250),
                        new TranscriptSegment(1, "미확인", "회의를 시작합니다.", 1500, 3100));
        assertThat(method).isEqualTo("POST");
        assertThat(authorization).isEqualTo("Bearer test-key");
        assertThat(contentType).startsWith("multipart/form-data; boundary=");
        String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
        assertThat(body).startsWith("--" + boundary + "\r\n").endsWith("--" + boundary + "--\r\n")
                .contains("name=\"model\"\r\n\r\nwhisper-1", "name=\"language\"\r\n\r\nko",
                        "name=\"response_format\"\r\n\r\nverbose_json", "name=\"timestamp_granularities[]\"\r\n\r\nsegment",
                        "filename=\"recording.webm\"", "Content-Type: audio/webm\r\n\r\naudio-bytes");
    }

    @Test
    void acceptsSafariAudioAndTextOnlyResponses() throws Exception {
        response = "{\"text\":\" 한국어 녹음 \"}";
        assertThat(client().transcribe(new byte[]{1, 2}, "audio/mp4"))
                .containsExactly(new TranscriptSegment(0, "미확인", "한국어 녹음", 0, 0));
        assertThat(body).contains("filename=\"recording.m4a\"");
    }

    @Test
    void rejectsSilenceMalformedResponsesAndRemoteErrorsWithoutExposingTheirBody() {
        for (String invalid : new String[]{"{}", "null", "{\"segments\":[],\"text\":\" \"}", "not-json"}) {
            response = invalid;
            assertThatThrownBy(() -> client().transcribe(new byte[]{1}, "audio/webm")).isInstanceOf(IOException.class);
        }
        status = 401;
        response = "secret-upstream-error";
        assertThatThrownBy(() -> client().transcribe(new byte[]{1}, "audio/webm"))
                .isInstanceOf(IOException.class).hasMessageContaining("401").hasMessageNotContaining(response);
    }

    @Test
    void rejectsMissingKeyEmptyAndOversizedAudioBeforeSending() {
        assertThatThrownBy(() -> new WhisperApiClient(json, baseUrl, "", Duration.ofSeconds(1))
                .transcribe(new byte[]{1}, "audio/webm")).hasMessageContaining("OPENAI_API_KEY");
        assertThatThrownBy(() -> client().transcribe(new byte[0], "audio/webm")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client().transcribe(new byte[WhisperApiClient.MAX_AUDIO_BYTES + 1], "audio/webm"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(body).isNull();
    }
}
