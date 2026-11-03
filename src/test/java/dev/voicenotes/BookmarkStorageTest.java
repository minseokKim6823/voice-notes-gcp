package dev.voicenotes;

import dev.voicenotes.domain.NoteEntity;
import dev.voicenotes.domain.TranscriptSegment;
import dev.voicenotes.persistence.MongoTranscriptStore;
import dev.voicenotes.persistence.NoteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses only the fixed isolated Compose databases. Never calls STT or summary APIs. */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "RUN_STORAGE_TESTS", matches = "true")
class BookmarkStorageTest {
    @Autowired NoteRepository repository;
    @Autowired MongoTemplate mongo;
    @Autowired MongoTranscriptStore transcripts;
    @Autowired MockMvc mvc;

    @DynamicPropertySource
    static void isolated(DynamicPropertyRegistry properties) {
        properties.add("voice-notes.analytics.enabled", () -> "false");
        properties.add("spring.datasource.url", () -> "jdbc:mariadb://127.0.0.1:53317/voice_notes_test");
        properties.add("spring.datasource.username", () -> "voice_notes_test");
        properties.add("spring.datasource.password", () -> "local-test-only");
        properties.add("spring.sql.init.mode", () -> "always");
        properties.add("spring.data.mongodb.uri", () -> "mongodb://127.0.0.1:53318/voice_notes_test?serverSelectionTimeoutMS=5000");
    }

    @Test
    void bookmarkSurvivesTranscriptReplacementAndRepeatedPutsDoNotDuplicateIt() throws Exception {
        String id = UUID.randomUUID().toString(), bookmark = UUID.randomUUID().toString();
        repository.save(new NoteEntity(id, "북마크 통합 검증", "a.wav", "audio/a.wav", "audio/wav"));
        try {
            String uri = "/api/notes/" + id + "/bookmarks/" + bookmark;
            for (int attempt = 0; attempt < 2; attempt++) {
                mvc.perform(put(uri).contentType("application/json").content("{\"positionMs\":1550,\"label\":\"출시 결정\"}"))
                        .andExpect(status().isOk());
            }
            assertThat(mongo.count(Query.query(Criteria.where("noteId").is(id)), "voice_note_bookmarks")).isEqualTo(1);
            transcripts.save(id, List.of(new TranscriptSegment(0, "1", "전사를 수정했습니다.", 1000, 3000)));
            mvc.perform(get("/api/notes/{id}/bookmarks", id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].label").value("출시 결정"))
                    .andExpect(jsonPath("$[0].positionMs").value(1550));
            mvc.perform(delete(uri)).andExpect(status().isNoContent());
            mvc.perform(get("/api/notes/{id}/bookmarks", id)).andExpect(jsonPath("$.length()").value(0));
            assertThat(transcripts.get(id)).hasSize(1);
        } finally {
            mongo.remove(Query.query(Criteria.where("noteId").is(id)), "voice_note_bookmarks");
            mongo.remove(Query.query(Criteria.where("_id").is(id)), "voice_note_transcripts");
            repository.deleteById(id);
        }
    }
}
