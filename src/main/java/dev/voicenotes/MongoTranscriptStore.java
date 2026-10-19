package dev.voicenotes;

import org.springframework.dao.DataAccessException;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class MongoTranscriptStore {
    static final String COLLECTION = "voice_note_transcripts";
    private final MongoTemplate mongo;

    public MongoTranscriptStore(MongoTemplate mongo) { this.mongo = mongo; }

    public record TranscriptDocument(@Id String id, List<TranscriptSegment> segments) {}

    public void save(String noteId, List<TranscriptSegment> segments) throws IOException {
        try {
            // One replacement per note keeps speaker edits and merges atomic on standalone MongoDB.
            mongo.save(new TranscriptDocument(noteId, List.copyOf(segments)), COLLECTION);
        } catch (DataAccessException e) {
            throw new IOException("전사 내용의 MongoDB 저장이 실패했습니다.", e);
        }
    }

    public List<TranscriptSegment> get(String noteId) throws IOException {
        try {
            TranscriptDocument document = mongo.findById(noteId, TranscriptDocument.class, COLLECTION);
            return document == null || document.segments() == null ? List.of()
                    : document.segments().stream().sorted(Comparator.comparingInt(TranscriptSegment::sequence)).toList();
        } catch (DataAccessException e) {
            throw new IOException("MongoDB에서 전사를 불러오지 못했습니다.", e);
        }
    }

    public Set<String> searchNoteIds(String text) throws IOException {
        if (text == null || text.isBlank()) return Set.of();
        try {
            // Escape user input: Korean substrings and regex metacharacters are literal search terms.
            Query query = Query.query(Criteria.where("segments.text")
                    .regex(Pattern.compile(Pattern.quote(text.strip()), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)));
            query.fields().include("_id");
            Set<String> ids = new LinkedHashSet<>();
            for (TranscriptDocument document : mongo.find(query, TranscriptDocument.class, COLLECTION)) ids.add(document.id());
            return ids;
        } catch (DataAccessException e) {
            throw new IOException("MongoDB 전사 검색이 실패했습니다.", e);
        }
    }
}
