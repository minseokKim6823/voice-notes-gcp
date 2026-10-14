package dev.voicenotes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ExaoneSummarizerTest {
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
