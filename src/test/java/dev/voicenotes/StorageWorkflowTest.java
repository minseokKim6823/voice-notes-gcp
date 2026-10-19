package dev.voicenotes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Explicit opt-in: isolated MariaDB and MongoDB in compose.test.yaml. */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "RUN_STORAGE_TESTS", matches = "true")
class StorageWorkflowTest {
    @Autowired NoteService notes;
    @Autowired NoteRepository repository;
    @Autowired MongoTranscriptStore transcripts;
    @Autowired org.springframework.data.mongodb.core.MongoTemplate mongo;
    @Autowired MockMvc mvc;
    @MockitoBean GoogleGateway google;
    @MockitoBean ExaoneSummarizer exaone;

    @DynamicPropertySource
    static void isolatedStorage(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:mariadb://127.0.0.1:53317/voice_notes_test");
        properties.add("spring.datasource.username", () -> "voice_notes_test");
        properties.add("spring.datasource.password", () -> "local-test-only");
        properties.add("spring.sql.init.mode", () -> "always");
        properties.add("spring.data.mongodb.uri", () -> "mongodb://127.0.0.1:53318/voice_notes_test?serverSelectionTimeoutMS=5000");
    }

    @Test
    void persistsDiarizationAndRetriesSummaryWithoutRepeatingSpeech() throws Exception {
        List<TranscriptSegment> segments = List.of(
                new TranscriptSegment(0, "1", "다음 주 금요일에 출시합니다.", 0, 1500),
                new TranscriptSegment(1, "2", "테스트를 목요일까지 완료하겠습니다. 👍", 1600, 3000));
        when(google.transcribe(anyString(), any())).thenReturn(segments);
        when(exaone.summarize(anyList(), any())).thenThrow(new IOException("temporary summary failure"))
                .thenReturn("금요일 출시, 목요일 테스트 완료. 👍");

        Note uploaded = notes.create("마리아DB 통합 테스트", new MockMultipartFile(
                "file", "meeting.webm", "audio/webm", new byte[] {1, 2, 3}));
        String id = uploaded.id();
        try {
            assertThat(repository.findById(id).orElseThrow().status).isEqualTo("UPLOADED");
            assertThatThrownBy(() -> notes.process(id)).isInstanceOf(IOException.class);
            assertThat(repository.findById(id).orElseThrow().status).isEqualTo("FAILED");
            assertThat(transcripts.get(id)).containsExactlyElementsOf(segments);

            Note completed = notes.process(id);
            assertThat(completed.status()).isEqualTo("READY");
            assertThat(repository.findById(id).orElseThrow().summary).contains("금요일", "👍");
            assertThat(notes.search("목요일까지")).extracting(Note::id).contains(id);
            mvc.perform(get("/api/notes/{id}", id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("READY"))
                    .andExpect(jsonPath("$.segments[1].speaker").value("2"))
                    .andExpect(jsonPath("$.segments[1].text").value(segments.get(1).text()));

            mvc.perform(patch("/api/notes/{id}/speakers/1", id).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"김민수 👍\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.summaryStale").value(true));
            assertThat(repository.findById(id).orElseThrow().summaryStale).isTrue();
            assertThat(transcripts.get(id).get(0).speakerName()).isEqualTo("김민수 👍");
            mvc.perform(patch("/api/notes/{id}/segments/1/speaker", id).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"새 참가자\"}"))
                    .andExpect(status().isOk());
            String customId = notes.get(id).segments().get(1).speaker();
            mvc.perform(post("/api/notes/{id}/speakers/{speaker}/merge", id, customId)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"targetSpeaker\":\"1\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.speakers.length()").value(1));
            mvc.perform(patch("/api/notes/{id}/segments/1", id).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"text\":\"수정검증특수단어 목요일 완료\"}"))
                    .andExpect(status().isOk());
            assertThat(notes.search("수정검증특수단어")).extracting(Note::id).contains(id);
            when(exaone.summarize(anyList(), any())).thenReturn("김민수 👍: 금요일 출시, 목요일 검증 완료.");
            mvc.perform(post("/api/notes/{id}/summary", id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.summaryStale").value(false));
            assertThat(repository.findById(id).orElseThrow().summaryStale).isFalse();
            assertThat(notes.get(id).transcript()).contains("김민수 👍: 수정검증특수단어 목요일 완료");
            verify(google, times(1)).transcribe(anyString(), any());
        } finally {
            repository.deleteById(id);
            mongo.remove(org.springframework.data.mongodb.core.query.Query.query(
                    org.springframework.data.mongodb.core.query.Criteria.where("_id").is(id)), MongoTranscriptStore.COLLECTION);
        }
    }

    @Test
    void addsSummaryFlagToLegacySchemaWithoutChangingSavedNotes() throws Exception {
        String table = "vn_migration_" + java.util.UUID.randomUUID().toString().replace("-", "");
        String schema;
        try (var stream = getClass().getResourceAsStream("/db/schema-mariadb.sql")) {
            schema = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace("voice_notes", table);
        }
        try (var connection = java.sql.DriverManager.getConnection(
                "jdbc:mariadb://127.0.0.1:53317/voice_notes_test", "voice_notes_test", "local-test-only");
             var statement = connection.createStatement()) {
            try {
                statement.execute(schema.substring(0, schema.indexOf(';'))
                        .replace("    summary_stale BOOLEAN NOT NULL DEFAULT FALSE,", "")
                        .replace("    progress_percent INT NOT NULL DEFAULT 0,", "")
                        .replace("    processing_stage VARCHAR(255) NOT NULL DEFAULT 'UPLOADED',", ""));
                statement.execute("INSERT INTO " + table + " (id,title,file_name,audio_object,content_type,created_at,status,summary) "
                        + "VALUES ('legacy','기존 노트','a.wav','audio/a.wav','audio/wav',NOW(),'READY','기존 요약')");
                for (int run = 0; run < 2; run++) {
                    for (String sql : schema.split(";")) if (!sql.isBlank()) statement.execute(sql);
                }
                try (var result = statement.executeQuery("SELECT title,summary,summary_stale,progress_percent,processing_stage FROM " + table + " WHERE id='legacy'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString("title")).isEqualTo("기존 노트");
                    assertThat(result.getString("summary")).isEqualTo("기존 요약");
                    assertThat(result.getBoolean("summary_stale")).isFalse();
                    assertThat(result.getInt("progress_percent")).isZero();
                    assertThat(result.getString("processing_stage")).isEqualTo("UPLOADED");
                }
            } finally {
                statement.execute("DROP TABLE IF EXISTS " + table);
            }
        }
    }
}
