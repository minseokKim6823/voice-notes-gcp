package dev.voicenotes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record Speaker(String id, String name, int segmentCount, long durationMs) {
    public static List<Speaker> from(List<TranscriptSegment> segments) {
        Map<String, Speaker> speakers = new LinkedHashMap<>();
        for (TranscriptSegment segment : segments) {
            Speaker previous = speakers.get(segment.speaker());
            int count = previous == null ? 0 : previous.segmentCount();
            long duration = previous == null ? 0 : previous.durationMs();
            speakers.put(segment.speaker(), new Speaker(segment.speaker(), segment.displayName(), count + 1,
                    duration + Math.max(0, segment.endMs() - segment.startMs())));
        }
        return new ArrayList<>(speakers.values());
    }
}
