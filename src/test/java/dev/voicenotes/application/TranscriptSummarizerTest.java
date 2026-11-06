package dev.voicenotes.application;

import dev.voicenotes.infrastructure.SummaryApiClient;
import dev.voicenotes.domain.TranscriptSegment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class TranscriptSummarizerTest {
    @Test
    void reportsCompletedChunksAndWaitsForTheFinalMergeBeforeReportingComplete() throws Exception {
        SummaryApiClient api = mock(SummaryApiClient.class);
        when(api.complete(anyString())).thenReturn("부분 요약", "부분 요약", "부분 요약", "부분 요약", "부분 요약", "통합 요약");
        var progress = new java.util.ArrayList<Integer>();
        String result = new TranscriptSummarizer(api).summarize(List.of(
                new TranscriptSegment(0, "1", "가".repeat(13000), 0, 0)), progress::add);
        assertThat(result).isEqualTo("통합 요약");
        assertThat(progress).isSorted().containsSubsequence(0, 70, 100);
        assertThat(progress.subList(0, progress.size() - 1)).allMatch(percent -> percent < 100);
        verify(api, times(6)).complete(anyString());
    }

    @Test
    void sendsEditedSpeakerNamesAndUnknownLabelsInTheSummaryPrompt() throws Exception {
        SummaryApiClient api = mock(SummaryApiClient.class);
        when(api.complete(anyString())).thenReturn("요약");
        assertThat(new TranscriptSummarizer(api).summarize(List.of(
                new TranscriptSegment(0, "1", "출시합니다.", 0, 1000, "민수"),
                new TranscriptSegment(1, "미확인", "확인했습니다.", 1000, 2000)))).isEqualTo("요약");
        verify(api).complete(argThat(prompt -> prompt.contains("민수: 출시합니다.")
                && prompt.contains("미확인 화자: 확인했습니다.") && !prompt.contains("화자 민수")));
    }
}
