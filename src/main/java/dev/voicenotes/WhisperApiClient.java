package dev.voicenotes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class WhisperApiClient {
    // Decimal bytes: stay within the upstream 25 MB limit, including multipart overhead.
    public static final int MAX_AUDIO_BYTES = 24_000_000;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;
    private final String baseUrl;
    private final String apiKey;
    private final Duration timeout;

    public WhisperApiClient(ObjectMapper json,
            @Value("${voice-notes.whisper.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${voice-notes.whisper.api-key:}") String apiKey,
            @Value("${voice-notes.whisper.timeout:10m}") Duration timeout) {
        this.json = json;
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        this.apiKey = apiKey.trim();
        this.timeout = timeout;
    }

    public void validateConfiguration() {
        if (apiKey.isBlank()) throw new IllegalStateException("OPENAI_API_KEY에 Whisper API 키를 설정하세요.");
        endpoint();
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalStateException("Whisper 시간 제한은 양수여야 합니다.");
    }

    private URI endpoint() {
        try {
            URI uri = URI.create(baseUrl + "/audio/transcriptions");
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("WHISPER_BASE_URL에는 유효한 HTTP(S) API 기본 주소를 설정하세요.");
        }
    }

    public List<TranscriptSegment> transcribe(byte[] audio, String contentType) throws IOException {
        validateConfiguration();
        if (audio.length == 0 || audio.length > MAX_AUDIO_BYTES) {
            throw new IllegalArgumentException("음성은 비어 있지 않은 24MB 이하 데이터여야 합니다.");
        }
        String extension = switch (contentType) {
            case "audio/webm" -> "webm";
            case "audio/mp4", "audio/x-m4a" -> "m4a";
            case "audio/wav", "audio/x-wav" -> "wav";
            case "audio/mpeg", "audio/mp3" -> "mp3";
            case "audio/flac" -> "flac";
            case "audio/ogg" -> "ogg";
            default -> throw new IllegalArgumentException("Whisper에서 지원하지 않는 음성 형식입니다.");
        };
        String boundary = "voice-notes-" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        field(body, boundary, "model", "whisper-1");
        field(body, boundary, "language", "ko");
        field(body, boundary, "response_format", "verbose_json");
        field(body, boundary, "timestamp_granularities[]", "segment");
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"recording."
                + extension + "\"\r\nContent-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(audio);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(endpoint()).timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Whisper API HTTP " + response.statusCode() + ": 인증 키, 사용 한도 및 음성 형식을 확인하세요.");
            }
            JsonNode result;
            try { result = json.readTree(response.body()); }
            catch (IOException e) { throw new IOException("Whisper 응답이 올바른 JSON 형식이 아닙니다."); }
            return parse(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Whisper 전사가 중단되었습니다.", e);
        }
    }

    private static void field(ByteArrayOutputStream body, String boundary, String name, String value) throws IOException {
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    static List<TranscriptSegment> parse(JsonNode result) throws IOException {
        var segments = new ArrayList<TranscriptSegment>();
        if (result != null) {
            for (JsonNode segment : result.path("segments")) {
                String text = segment.path("text").asText("").trim();
                if (text.isBlank()) continue;
                long start = millis(segment.path("start"));
                long end = Math.max(start, millis(segment.path("end")));
                segments.add(new TranscriptSegment(segments.size(), "미확인", text, start, end));
            }
            if (segments.isEmpty() && result.path("text").isTextual() && !result.path("text").asText().isBlank()) {
                segments.add(new TranscriptSegment(0, "미확인", result.path("text").asText().trim(), 0, 0));
            }
        }
        if (segments.isEmpty()) throw new IOException("인식된 음성이 없습니다. 마이크와 녹음 내용을 확인해 주세요.");
        return segments;
    }

    private static long millis(JsonNode value) {
        double seconds = value.asDouble(0);
        return Double.isFinite(seconds) && seconds >= 0 ? Math.round(seconds * 1000) : 0;
    }
}
