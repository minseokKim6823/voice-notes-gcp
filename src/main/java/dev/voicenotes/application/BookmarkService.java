package dev.voicenotes.application;

import dev.voicenotes.domain.NoteBookmark;
import dev.voicenotes.persistence.NoteRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;

@Service
public class BookmarkService {
    private static final String COLLECTION = "voice_note_bookmarks";
    private final NoteRepository notes;
    private final MongoTemplate mongo;
    public BookmarkService(NoteRepository notes, MongoTemplate mongo) { this.notes = notes; this.mongo = mongo; }
    public record Document(@Id String id, String noteId, NoteBookmark bookmark) {}

    private void requireNote(String noteId) {
        if (noteId == null || !noteId.matches("[0-9a-fA-F-]{36}") || !notes.existsById(noteId)) {
            throw new IllegalArgumentException("노트를 찾을 수 없습니다.");
        }
    }
    private String key(String noteId, String id) {
        try { java.util.UUID.fromString(id); } catch (RuntimeException e) { throw new IllegalArgumentException("잘못된 북마크 ID입니다."); }
        return noteId + ":" + id;
    }
    public List<NoteBookmark> list(String noteId) throws IOException {
        requireNote(noteId);
        try {
            return mongo.find(Query.query(Criteria.where("noteId").is(noteId)), Document.class, COLLECTION)
                    .stream().map(Document::bookmark).sorted(Comparator.comparingLong(NoteBookmark::positionMs)).toList();
        } catch (DataAccessException e) { throw new IOException("북마크를 불러오지 못했습니다.", e); }
    }
    public synchronized NoteBookmark put(String noteId, String id, long positionMs, String label) throws IOException {
        requireNote(noteId);
        String key = key(noteId, id);
        String text = label == null ? "중요한 순간" : label.strip();
        if (positionMs < 0 || positionMs > 86_400_000 || text.isBlank() || text.length() > 80
                || text.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("북마크 시간은 0~24시간, 이름은 줄바꿈 없이 1~80자로 입력하세요.");
        try {
            if (!mongo.exists(Query.query(Criteria.where("_id").is(key)), Document.class, COLLECTION)
                    && mongo.count(Query.query(Criteria.where("noteId").is(noteId)), COLLECTION) >= 200) {
                throw new IllegalArgumentException("북마크는 노트당 200개까지 저장할 수 있습니다.");
            }
            var bookmark = new NoteBookmark(id, positionMs, text);
            mongo.save(new Document(key, noteId, bookmark), COLLECTION);
            return bookmark;
        } catch (DataAccessException e) { throw new IOException("북마크를 저장하지 못했습니다. 다시 시도해 주세요.", e); }
    }
    public void delete(String noteId, String id) throws IOException {
        requireNote(noteId);
        String key = key(noteId, id);
        try { mongo.remove(Query.query(Criteria.where("_id").is(key)), COLLECTION); }
        catch (DataAccessException e) { throw new IOException("북마크를 삭제하지 못했습니다.", e); }
    }
}
