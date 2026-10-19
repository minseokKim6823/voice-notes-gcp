package dev.voicenotes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ExaoneSummarizerTest {
    @Test
    void reportsCompletedChunksAndWaitsForTheFinalMergeBeforeReportingComplete() throws Exception {
        ExaoneApiClient api = mock(ExaoneApiClient.class);
        when(api.complete(anyString())).thenReturn("부분 요약", "부분 요약", "통합 요약");
        var progress = new java.util.ArrayList<Integer>();
        String result = new ExaoneSummarizer(api).summarize(List.of(
                new TranscriptSegment(0, "1", "가".repeat(13000), 0, 0)), progress::add);
        assertThat(result).isEqualTo("통합 요약");
        assertThat(progress).containsSubsequence(0, 33, 66, 100);
        verify(api, times(3)).complete(anyString());
    }

    @Test
    void sendsEditedSpeakerNamesAndUnknownLabelsInTheSummaryPrompt() throws Exception {
        ExaoneApiClient api = mock(ExaoneApiClient.class);
        when(api.complete(anyString())).thenReturn("요약");
        assertThat(new ExaoneSummarizer(api).summarize(List.of(
                new TranscriptSegment(0, "1", "출시합니다.", 0, 1000, "민수"),
                new TranscriptSegment(1, "미확인", "확인했습니다.", 1000, 2000)))).isEqualTo("요약");
        verify(api).complete(argThat(prompt -> prompt.contains("민수: 출시합니다.")
                && prompt.contains("미확인 화자: 확인했습니다.") && !prompt.contains("화자 민수")));
    }
}
