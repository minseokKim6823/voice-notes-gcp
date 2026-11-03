package dev.voicenotes;

import dev.voicenotes.api.BookmarkController;
import dev.voicenotes.application.BookmarkService;
import dev.voicenotes.domain.NoteBookmark;
import dev.voicenotes.persistence.NoteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BookmarkServiceTest {
    private final NoteRepository notes = mock(NoteRepository.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final BookmarkService service = new BookmarkService(notes, mongo);
    private final String note = UUID.randomUUID().toString();
    private final String id = UUID.randomUUID().toString();

    @Test
    void storesBookmarksOutsideTheTranscriptAndRetriesUsingTheSameDocumentKey() throws Exception {
        when(notes.existsById(note)).thenReturn(true);
        var bookmark = service.put(note, id, 1250, "  확인 필요  ");
        assertThat(bookmark).isEqualTo(new NoteBookmark(id, 1250, "확인 필요"));
        service.put(note, id, 1250, "확인 필요");
        verify(mongo, times(2)).save(new BookmarkService.Document(note + ":" + id, note, bookmark), "voice_note_bookmarks");
        verify(mongo, never()).save(any(), eq("voice_note_transcripts"));
        service.delete(note, id);
        var query = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(mongo).remove(query.capture(), eq("voice_note_bookmarks"));
        assertThat(query.getValue().getQueryObject().getString("_id")).isEqualTo(note + ":" + id);
    }

    @Test
    void restoresBookmarksInTimelineOrder() throws Exception {
        when(notes.existsById(note)).thenReturn(true);
        var early = new NoteBookmark(id, 1500, "처음");
        var later = new NoteBookmark(UUID.randomUUID().toString(), 4500, "다음");
        when(mongo.find(any(Query.class), eq(BookmarkService.Document.class), eq("voice_note_bookmarks")))
                .thenReturn(List.of(new BookmarkService.Document("b", note, later), new BookmarkService.Document("a", note, early)));
        assertThat(service.list(note)).containsExactly(early, later);
    }

    @Test
    void rejectsInvalidInputMissingNotesAndOverCapacityWithoutWriting() {
        assertThatThrownBy(() -> service.put(note, id, 0, "중요")).hasMessageContaining("노트");
        when(notes.existsById(note)).thenReturn(true);
        assertThatThrownBy(() -> service.put(note, "bad-id", 0, "중요")).hasMessageContaining("ID");
        assertThatThrownBy(() -> service.put(note, id, -1, "중요")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.put(note, id, 100, "줄\n바꿈")).isInstanceOf(IllegalArgumentException.class);
        when(mongo.count(any(Query.class), anyString())).thenReturn(200L);
        assertThatThrownBy(() -> service.put(note, id, 100, "중요")).hasMessageContaining("200");
        verify(mongo, never()).save(any(), anyString());
    }

    @Test
    void databaseFailureReturnsAnActionableErrorWithoutPrivateDatabaseDetails() throws Exception {
        when(notes.existsById(note)).thenReturn(true);
        when(mongo.save(any(), anyString())).thenThrow(new DataAccessResourceFailureException("private-db-password"));
        var mvc = MockMvcBuilders.standaloneSetup(new BookmarkController(service)).build();
        mvc.perform(put("/api/notes/{note}/bookmarks/{id}", note, id).contentType("application/json")
                .content("{\"positionMs\":1500,\"label\":\"확인\"}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.error").value("북마크를 저장하지 못했습니다. 다시 시도해 주세요."));
    }
}
