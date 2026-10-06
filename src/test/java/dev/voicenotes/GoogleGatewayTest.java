package dev.voicenotes;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleGatewayTest {
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
}
