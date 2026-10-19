package dev.voicenotes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
import java.util.function.IntConsumer;

@Component
public class GoogleGateway {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper json;
    private final String project;
    private final String bucket;
    private final String speechLocation;
    private final String speechModel;
    private final boolean wordTimeOffsets;
    private GoogleCredentials credentials;

    public GoogleGateway(ObjectMapper json,
            @Value("${voice-notes.project-id}") String project,
            @Value("${voice-notes.bucket}") String bucket,
            @Value("${voice-notes.speech-location}") String speechLocation,
            @Value("${voice-notes.speech-model}") String speechModel,
            @Value("${voice-notes.speech-word-time-offsets:false}") boolean wordTimeOffsets) {
        this.json = json;
        this.project = project;
        this.bucket = bucket;
        this.speechLocation = speechLocation;
        this.speechModel = speechModel;
        this.wordTimeOffsets = wordTimeOffsets;
    }

    public String bucket() { return bucket; }

    private void configured() {
        if (project.isBlank() || bucket.isBlank()) {
            throw new IllegalStateException("GOOGLE_CLOUD_PROJECT와 VOICE_NOTES_BUCKET을 설정하세요.");
        }
    }

    private synchronized Map<String, List<String>> authorization(URI uri) throws IOException {
        configured();
        if (credentials == null) {
            credentials = GoogleCredentials.getApplicationDefault()
                    .createScoped(List.of("https://www.googleapis.com/auth/cloud-platform"));
        }
        // Preserve ADC quota-project metadata as well as the refreshed access token.
        return credentials.getRequestMetadata(uri);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private HttpResponse<byte[]> call(String method, String url, byte[] body, String contentType) throws IOException {
        try {
            URI uri = URI.create(url);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(3));
            authorization(uri).forEach((header, values) -> values.forEach(value -> builder.header(header, value)));
            if (contentType != null) builder.header("Content-Type", contentType);
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 300) {
                String detail = new String(response.body(), StandardCharsets.UTF_8);
                throw new IOException("Google API HTTP " + response.statusCode() + ": " + detail.substring(0, Math.min(500, detail.length())));
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Google API 요청이 중단되었습니다.", e);
        }
    }

    private JsonNode jsonCall(String method, String url, Object body) throws IOException {
        byte[] data = body == null ? null : json.writeValueAsBytes(body);
        return json.readTree(call(method, url, data, body == null ? null : "application/json").body());
    }

    public void put(String name, byte[] bytes, String contentType) throws IOException {
        call("POST", "https://storage.googleapis.com/upload/storage/v1/b/" + enc(bucket)
                + "/o?uploadType=media&name=" + enc(name), bytes, contentType);
    }

    public byte[] get(String name) throws IOException {
        return call("GET", "https://storage.googleapis.com/download/storage/v1/b/" + enc(bucket)
                + "/o/" + enc(name) + "?alt=media", null, null).body();
    }

    public List<TranscriptSegment> transcribe(String objectName) throws IOException {
        return transcribe(objectName, percent -> {});
    }

    public List<TranscriptSegment> transcribe(String objectName, IntConsumer progress) throws IOException {
        progress.accept(0);
        String uri = "gs://" + bucket + "/" + objectName;
        String recognizer = "projects/" + project + "/locations/" + speechLocation + "/recognizers/_";
        String speechBase = "https://" + speechLocation + "-speech.googleapis.com/v2/";
        JsonNode operation = jsonCall("POST", speechBase + recognizer + ":batchRecognize",
                Map.of("config", recognitionConfig(),
                        "files", List.of(Map.of("uri", uri)), "recognitionOutputConfig", Map.of("inlineResponseConfig", Map.of())));
        String name = operation.path("name").asText();
        if (name.isBlank()) throw new IOException("전사 작업 ID가 없습니다.");
        for (int i = 0; i < 180; i++) {
            JsonNode state = jsonCall("GET", speechBase + name, null);
            progress.accept(operationProgress(state));
            if (state.path("done").asBoolean()) {
                if (state.has("error")) throw new IOException("전사 실패: " + state.path("error").path("message").asText());
                List<TranscriptSegment> segments = parseBatchFileResult(state.path("response").path("results").path(uri));
                progress.accept(100);
                return segments;
            }
            try { Thread.sleep(5000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("전사 대기가 중단되었습니다.", e); }
        }
        throw new IOException("전사가 15분 안에 끝나지 않았습니다. 다시 처리할 수 있습니다.");
    }

    Map<String, Object> recognitionConfig() {
        return Map.of("autoDecodingConfig", Map.of(), "languageCodes", List.of("ko-KR"),
                "model", speechModel, "features", Map.of("diarizationConfig", Map.of(),
                "enableAutomaticPunctuation", true, "enableWordTimeOffsets", wordTimeOffsets));
    }

    static int operationProgress(JsonNode operation) {
        return Math.max(0, Math.min(100, operation.path("metadata").path("progressPercent").asInt()));
    }

    static List<TranscriptSegment> parseBatchFileResult(JsonNode fileResult) throws IOException {
        if (fileResult.path("error").path("code").asInt() != 0) {
            throw new IOException("음성 파일 전사 실패: " + fileResult.path("error").path("message").asText());
        }
        JsonNode transcript = fileResult.path("inlineResult").path("transcript");
        if (transcript.isMissingNode()) transcript = fileResult.path("transcript");
        List<TranscriptSegment> segments = parseResults(transcript.path("results"));
        if (segments.isEmpty()) throw new IOException("전사 결과가 비어 있습니다. 지원되는 음성 형식인지 확인하세요.");
        return segments;
    }

    static List<TranscriptSegment> parseResults(JsonNode results) {
        List<TranscriptSegment> segments = new ArrayList<>();
        for (JsonNode result : results) {
            JsonNode alternatives = result.path("alternatives");
            if (!alternatives.isArray() || alternatives.isEmpty()) continue;
            JsonNode top = alternatives.get(0);
            JsonNode words = top.path("words");
            if (!words.isArray() || words.isEmpty()) {
                String text = top.path("transcript").asText().trim();
                if (!text.isBlank()) segments.add(new TranscriptSegment(segments.size(), "미확인", text, 0, 0));
                continue;
            }
            String speaker = null;
            StringBuilder line = new StringBuilder();
            long start = 0;
            long end = 0;
            int firstSequence = segments.size();
            for (JsonNode word : words) {
                String token = word.path("word").asText().trim();
                if (token.isBlank()) continue;
                String nextSpeaker = word.path("speakerLabel").asText("미확인").trim();
                if (nextSpeaker.isBlank()) nextSpeaker = "미확인";
                long wordStart = millis(word.path("startOffset").asText());
                if (speaker != null && !line.isEmpty()
                        && (!speaker.equals(nextSpeaker) || (end > 0 && wordStart - end >= 1500))) {
                    segments.add(new TranscriptSegment(segments.size(), speaker, line.toString().trim(), start, end));
                    line.setLength(0);
                }
                if (line.isEmpty()) start = wordStart;
                speaker = nextSpeaker;
                line.append(token).append(' ');
                end = millis(word.path("endOffset").asText());
            }
            if (!line.isEmpty()) segments.add(new TranscriptSegment(segments.size(), speaker, line.toString().trim(), start, end));
            if (segments.size() == firstSequence) {
                String text = top.path("transcript").asText().trim();
                if (!text.isBlank()) segments.add(new TranscriptSegment(segments.size(), "미확인", text, 0, 0));
            }
        }
        return segments;
    }

    private static long millis(String duration) {
        if (!duration.endsWith("s")) return 0;
        try {
            double seconds = Double.parseDouble(duration.substring(0, duration.length() - 1));
            return Double.isFinite(seconds) && seconds >= 0 ? Math.round(seconds * 1000) : 0;
        }
        catch (NumberFormatException e) { return 0; }
    }
}
