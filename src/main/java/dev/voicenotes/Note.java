package dev.voicenotes;

import java.time.Instant;
import java.util.List;

public record Note(String id, String title, String fileName, String audioObject, String contentType,
                   Instant createdAt, String status, String transcript, String summary, String error,
                   List<TranscriptSegment> segments) {
    public static Note from(NoteEntity entity, List<TranscriptSegment> segments) {
        StringBuilder text = new StringBuilder();
        for (TranscriptSegment segment : segments) {
            text.append("화자 ").append(segment.speaker()).append(": ").append(segment.text()).append('\n');
        }
        return new Note(entity.id, entity.title, entity.fileName, entity.audioObject, entity.contentType,
                entity.createdAt, entity.status, text.toString().trim(), entity.summary, entity.error, segments);
    }
}
