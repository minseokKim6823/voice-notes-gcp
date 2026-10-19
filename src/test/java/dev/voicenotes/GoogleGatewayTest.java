package dev.voicenotes;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleGatewayTest {
    @org.junit.jupiter.api.Test
    void readsOperationProgressAndClampsInvalidUpstreamValues() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        org.assertj.core.api.Assertions.assertThat(GoogleGateway.operationProgress(json.readTree("{\"metadata\":{\"progressPercent\":37}}"))).isEqualTo(37);
        org.assertj.core.api.Assertions.assertThat(GoogleGateway.operationProgress(json.readTree("{\"metadata\":{\"progressPercent\":999}}"))).isEqualTo(100);
        org.assertj.core.api.Assertions.assertThat(GoogleGateway.operationProgress(json.readTree("{}"))).isZero();
    }

    @Test
    void requestsDiarizationAndOptionalWordTimestamps() {
        var precise = new GoogleGateway(new ObjectMapper(), "test-project", "test-bucket", "us", "chirp_3", true);
        var standard = new GoogleGateway(new ObjectMapper(), "test-project", "test-bucket", "us", "chirp_3", false);
        var json = new ObjectMapper();
        assertThat(json.valueToTree(precise.recognitionConfig()).path("features").path("enableWordTimeOffsets").asBoolean()).isTrue();
        assertThat(json.valueToTree(standard.recognitionConfig()).path("features").path("enableWordTimeOffsets").asBoolean()).isFalse();
        assertThat(json.valueToTree(precise.recognitionConfig()).path("features").path("diarizationConfig").isObject()).isTrue();
    }

    @Test
    void readsCurrentInlineBatchResponse() throws Exception {
        String result = """
            {"inlineResult":{"transcript":{"results":[{"alternatives":[{
              "words":[{"word":"회의입니다", "speakerLabel":"2"}]
            }]}]}}}
            """;
        assertThat(GoogleGateway.parseBatchFileResult(new ObjectMapper().readTree(result)))
                .containsExactly(new TranscriptSegment(0, "2", "회의입니다", 0, 0));
    }

    @Test
    void acceptsLegacyBatchTranscriptField() throws Exception {
        String result = "{\"transcript\":{\"results\":[{\"alternatives\":[{\"transcript\":\"기존 응답\"}]}]}}";
        assertThat(GoogleGateway.parseBatchFileResult(new ObjectMapper().readTree(result)))
                .containsExactly(new TranscriptSegment(0, "미확인", "기존 응답", 0, 0));
    }

    @Test
    void reportsFileErrorsInsideSuccessfulBatchOperation() throws Exception {
        var result = new ObjectMapper().readTree("{\"error\":{\"code\":3,\"message\":\"Unsupported audio\"}}");
        assertThatThrownBy(() -> GoogleGateway.parseBatchFileResult(result))
                .isInstanceOf(IOException.class).hasMessageContaining("Unsupported audio");
    }

    @Test
    void groupsDiarizedWordsBySpeakerInOrder() throws Exception {
        String apiResults = """
            [
              {"alternatives":[{"transcript":"안녕하세요 반갑습니다 네 맞아요", "words":[
                {"word":"안녕하세요", "speakerLabel":"1", "startOffset":"0s", "endOffset":"0.5s"},
                {"word":"반갑습니다", "speakerLabel":"1", "startOffset":"0.5s", "endOffset":"1.2s"},
                {"word":"네", "speakerLabel":"2", "startOffset":"1.3s", "endOffset":"1.5s"},
                {"word":"맞아요", "speakerLabel":"2", "startOffset":"1.5s", "endOffset":"2s"}
              ]}]}
            ]
            """;

        List<TranscriptSegment> segments = GoogleGateway.parseResults(new ObjectMapper().readTree(apiResults));

        assertThat(segments).containsExactly(
                new TranscriptSegment(0, "1", "안녕하세요 반갑습니다", 0, 1200),
                new TranscriptSegment(1, "2", "네 맞아요", 1300, 2000));
    }

    @Test
    void keepsTranscriptWhenWordLabelsAreMissing() throws Exception {
        String apiResults = "[{\"alternatives\":[{\"transcript\":\"테스트 음성\"}]}]";
        List<TranscriptSegment> segments = GoogleGateway.parseResults(new ObjectMapper().readTree(apiResults));
        assertThat(segments).containsExactly(new TranscriptSegment(0, "미확인", "테스트 음성", 0, 0));
    }

    @Test
    void splitsSameSpeakerAfterLongPauseAndNormalizesMissingLabels() throws Exception {
        var results = new ObjectMapper().readTree("""
                [{"alternatives":[{"words":[
                  {"word":"첫 발언","speakerLabel":" 1 ","startOffset":"0s","endOffset":"1s"},
                  {"word":"다음 발언","speakerLabel":"1","startOffset":"3s","endOffset":"4s"},
                  {"word":"누구죠","speakerLabel":" ","startOffset":"4s","endOffset":"5s"}]}]}]
                """);
        assertThat(GoogleGateway.parseResults(results)).containsExactly(
                new TranscriptSegment(0, "1", "첫 발언", 0, 1000),
                new TranscriptSegment(1, "1", "다음 발언", 3000, 4000),
                new TranscriptSegment(2, "미확인", "누구죠", 4000, 5000));
    }

    @Test
    void fallsBackToTranscriptWhenWordEntriesAreBlank() throws Exception {
        var results = new ObjectMapper().readTree("""
                [{"alternatives":[{"transcript":"남겨야 하는 전사","words":[{"word":"  "},{}]}]}]
                """);
        assertThat(GoogleGateway.parseResults(results)).containsExactly(new TranscriptSegment(0, "미확인", "남겨야 하는 전사", 0, 0));
    }

    @Test
    void invalidTimesDoNotProduceNegativeOrInfiniteOffsets() throws Exception {
        var results = new ObjectMapper().readTree("""
                [{"alternatives":[{"words":[{"word":"발언","startOffset":"-1s","endOffset":"Infinitys"}]}]}]
                """);
        assertThat(GoogleGateway.parseResults(results)).containsExactly(new TranscriptSegment(0, "미확인", "발언", 0, 0));
    }
}
