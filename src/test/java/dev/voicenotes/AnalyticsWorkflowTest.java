package dev.voicenotes;

import dev.voicenotes.api.*;
import dev.voicenotes.application.*;
import dev.voicenotes.domain.*;
import dev.voicenotes.persistence.*;
import dev.voicenotes.infrastructure.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalyticsWorkflowTest {
    @Test
    void correlatesFailureRetryRegenerationAndActualTokenUsageWithoutPrivateContent() throws Exception {
        var store = mock(ClickHouseAnalytics.class);
        var events = new ArrayList<ProcessingAnalytics.Event>();
        doAnswer(call -> { events.add(call.getArgument(0)); return null; }).when(store).record(any());
        var analytics = new ProcessingAnalytics(store);
        var response = new AtomicReference<>("""
                {"usage":{"prompt_tokens":100,"completion_tokens":20},
                 "choices":[{"finish_reason":"length","message":{"content":"private summary"}}]}
                """);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var settings = new SummaryModelSettings(new org.springframework.mock.env.MockEnvironment()
                    .withProperty("voice-notes.summary.provider", "compatible")
                    .withProperty("voice-notes.summary.base-url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .withProperty("voice-notes.summary.api-key", "test-key")
                    .withProperty("voice-notes.summary.model", "test-model"));
            var api = new SummaryApiClient(new ObjectMapper(), settings, analytics);
            var google = mock(GoogleGateway.class);
            var transcripts = mock(MongoTranscriptStore.class);
            var repository = mock(NoteRepository.class);
            var note = new NoteEntity(UUID.randomUUID().toString(), "private title", "a.wav", "audio/a.wav", "audio/wav");
            var segments = List.of(new TranscriptSegment(0, "1", "private transcript", 0, 1000));
            var saved = new AtomicReference<List<TranscriptSegment>>(List.of());
            when(repository.findById(note.id)).thenReturn(Optional.of(note));
            when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
            when(transcripts.get(note.id)).thenAnswer(call -> saved.get());
            doAnswer(call -> { saved.set(call.getArgument(1)); return null; }).when(transcripts).save(eq(note.id), anyList());
            when(google.transcribe(anyString(), any())).thenReturn(segments);
            var service = new NoteService(google, transcripts, new TranscriptSummarizer(api), repository,
                    new SpeechTranscriber(google, null, "google", "chirp_3", analytics), analytics);

            assertThatThrownBy(() -> service.process(note.id)).isInstanceOf(IOException.class);
            assertThat(events).extracting(ProcessingAnalytics.Event::stage).containsExactly("transcription", "summary_request", "workflow");
            String firstAttempt = events.get(0).attempt_id();
            assertThat(events).allMatch(event -> event.attempt_id().equals(firstAttempt) && event.note_id().equals(note.id));
            assertThat(events.get(1).success()).isZero();
            assertThat(events.get(1).input_tokens()).isEqualTo(100);
            assertThat(events.get(1).output_tokens()).isEqualTo(20);
            assertThat(events.get(2).success()).isZero();

            response.set("""
                    {"usage":{"prompt_tokens":80,"completion_tokens":10},
                     "choices":[{"finish_reason":"stop","message":{"content":"private summary"}}]}
                    """);
            assertThat(service.process(note.id).status()).isEqualTo("READY");
            assertThat(events).hasSize(5);
            assertThat(events.subList(3, 5)).allMatch(event -> event.action().equals("retry") && event.success() == 1);
            assertThat(events.get(3).attempt_id()).isNotEqualTo(firstAttempt).isEqualTo(events.get(4).attempt_id());
            service.process(note.id); // Already-ready calls are not new processing attempts.
            assertThat(events).hasSize(5);

            response.set("{\"choices\":[{\"message\":{\"content\":\"private summary\"}}]}");
            service.regenerateSummary(note.id);
            assertThat(events).hasSize(7);
            assertThat(events.get(5).action()).isEqualTo("regenerate");
            assertThat(events.get(5).input_tokens()).isNull();
            assertThat(events.get(5).output_tokens()).isNull();
            verify(google, times(1)).transcribe(anyString(), any());
            assertThat(events).allMatch(event -> event.duration_ms() >= 0);
            assertThat(new ObjectMapper().writeValueAsString(events)).doesNotContain("private", "test-key", "audio/a.wav");
            try (var unrelated = analytics.stage("summary_request", "exaone", "other")) { unrelated.success(); }
            assertThat(events).hasSize(7); // Request-local correlation was cleared.
        } finally { server.stop(0); }
    }
}
