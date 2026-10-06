package dev.voicenotes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Explicit opt-in: compose.test.yaml MariaDB + local compose.yaml Elasticsearch. */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "RUN_STORAGE_TESTS", matches = "true")
class StorageWorkflowTest {
    @Autowired NoteService notes;
    @Autowired NoteRepository repository;
    @Autowired ElasticsearchGateway elasticsearch;
    @Autowired MockMvc mvc;
    @MockitoBean GoogleGateway google;
    @MockitoBean ExaoneSummarizer exaone;

    @DynamicPropertySource
    static void isolatedStorage(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:mariadb://127.0.0.1:53317/voice_notes_test");
        properties.add("spring.datasource.username", () -> "voice_notes_test");
        properties.add("spring.datasource.password", () -> "local-test-only");
        properties.add("voice-notes.elasticsearch-url", () -> "http://127.0.0.1:9202");
        properties.add("voice-notes.elasticsearch-api-key", () -> "");
    }

    @Test
    void persistsDiarizationAndRetriesSummaryWithoutRepeatingSpeech() throws Exception {
        List<TranscriptSegment> segments = List.of(
                new TranscriptSegment(0, "1", "다음 주 금요일에 출시합니다.", 0, 1500),
                new TranscriptSegment(1, "2", "테스트를 목요일까지 완료하겠습니다. 👍", 1600, 3000));
        when(google.transcribe(anyString())).thenReturn(segments);
        when(exaone.summarize(anyList())).thenThrow(new IOException("temporary summary failure"))
                .thenReturn("금요일 출시, 목요일 테스트 완료. 👍");

        Note uploaded = notes.create("마리아DB 통합 테스트", new MockMultipartFile(
                "file", "meeting.webm", "audio/webm", new byte[] {1, 2, 3}));
        String id = uploaded.id();
        try {
            assertThat(repository.findById(id).orElseThrow().status).isEqualTo("UPLOADED");
            assertThatThrownBy(() -> notes.process(id)).isInstanceOf(IOException.class);
            assertThat(repository.findById(id).orElseThrow().status).isEqualTo("FAILED");
            assertThat(elasticsearch.get(id)).containsExactlyElementsOf(segments);

            Note completed = notes.process(id);
            assertThat(completed.status()).isEqualTo("READY");
            assertThat(repository.findById(id).orElseThrow().summary).contains("금요일", "👍");
            assertThat(notes.search("목요일까지")).extracting(Note::id).contains(id);
            mvc.perform(get("/api/notes/{id}", id)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("READY"))
                    .andExpect(jsonPath("$.segments[1].speaker").value("2"))
                    .andExpect(jsonPath("$.segments[1].text").value(segments.get(1).text()));
            verify(google, times(1)).transcribe(anyString());
        } finally {
            repository.deleteById(id);
            HttpClient http = HttpClient.newHttpClient();
            for (TranscriptSegment segment : segments) {
                HttpResponse<Void> result = http.send(HttpRequest.newBuilder(URI.create(
                                "http://127.0.0.1:9202/voice-note-segments/_doc/" + id + "-" + segment.sequence() + "?refresh=true"))
                        .DELETE().build(), HttpResponse.BodyHandlers.discarding());
                assertThat(result.statusCode()).isIn(200, 404);
            }
        }
    }
}
