package dev.voicenotes;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Component
public class ExaoneSummarizer {
    private final ExaoneApiClient api;

    public ExaoneSummarizer(ExaoneApiClient api) { this.api = api; }

    public String summarize(List<TranscriptSegment> segments) throws IOException {
        StringBuilder transcript = new StringBuilder();
        for (TranscriptSegment segment : segments) {
            transcript.append(segment.displayName()).append(": ").append(segment.text()).append('\n');
        }
        List<String> partial = new ArrayList<>();
        String text = transcript.toString();
        for (int start = 0; start < text.length(); start += 12000) {
            String chunk = text.substring(start, Math.min(start + 12000, text.length()));
            partial.add(api.complete("다음 한국어 대화 녹취를 요약하세요. 화자별 발언을 구분하고 핵심 내용, 결정 사항, 할 일을 적으세요. 없는 정보는 만들지 마세요.\n\n" + chunk));
        }
        if (partial.isEmpty()) throw new IOException("요약할 전사 내용이 없습니다.");
        return partial.size() == 1 ? partial.get(0)
                : api.complete("다음 부분별 요약을 하나의 한국어 노트로 통합하세요. 화자, 핵심 내용, 결정 사항, 할 일을 보존하고 중복을 제거하세요.\n\n"
                    + String.join("\n\n", partial));
    }
}
