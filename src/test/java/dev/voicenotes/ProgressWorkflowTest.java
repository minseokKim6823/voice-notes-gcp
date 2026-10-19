package dev.voicenotes;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProgressWorkflowTest {
    private final GoogleGateway google = mock(GoogleGateway.class);
    private final MongoTranscriptStore transcripts = mock(MongoTranscriptStore.class);
    private final ExaoneSummarizer exaone = mock(ExaoneSummarizer.class);
    private final NoteRepository repository = mock(NoteRepository.class);
    private final NoteService service = new NoteService(google, transcripts, exaone, repository);
    private final NoteEntity note = new NoteEntity(UUID.randomUUID().toString(), "회의", "a.wav", "audio/a.wav", "audio/wav");

    @Test
    void exposesRealProgressWhileTheProcessingRequestIsStillRunning() throws Exception {
        var stored = new AtomicReference<List<TranscriptSegment>>(List.of());
        var history = Collections.synchronizedList(new ArrayList<NoteProgress>());
        when(repository.findById(note.id)).thenReturn(Optional.of(note));
        when(repository.save(any())).thenAnswer(call -> { history.add(NoteProgress.from(note)); return note; });
        when(transcripts.get(note.id)).thenAnswer(call -> stored.get());
        doAnswer(call -> { stored.set(call.getArgument(1)); return null; }).when(transcripts).save(eq(note.id), anyList());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(google.transcribe(anyString(), any())).thenAnswer(call -> {
            IntConsumer progress = call.getArgument(1);
            progress.accept(50);
            progress.accept(10); // Upstream regressions must not move the bar backwards.
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("test timed out");
            progress.accept(100);
            return List.of(new TranscriptSegment(0, "1", "금요일 출시", 0, 1000));
        });
        when(exaone.summarize(anyList(), any())).thenAnswer(call -> {
            ((IntConsumer) call.getArgument(1)).accept(50);
            return "요약";
        });
        var processing = CompletableFuture.supplyAsync(() -> {
            try { return service.process(note.id); } catch (IOException e) { throw new RuntimeException(e); }
        });
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(processing.isDone()).isFalse();
            MockMvcBuilders.standaloneSetup(new NoteController(service)).build()
                    .perform(get("/api/notes/{id}/progress", note.id)).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.status").value("PROCESSING"))
                    .andExpect(jsonPath("$.processingStage").value("TRANSCRIBING"))
                    .andExpect(jsonPath("$.progressPercent").value(40));
        } finally { release.countDown(); }
        Note completed = processing.get(10, TimeUnit.SECONDS);
        assertThat(completed.progressPercent()).isEqualTo(100);
        assertThat(completed.processingStage()).isEqualTo("COMPLETE");
        assertThat(history).extracting(NoteProgress::progressPercent).isSorted();
        assertThat(history).filteredOn(p -> !p.status().equals("READY"))
                .allMatch(p -> p.progressPercent() < 100);
        assertThat(history).extracting(NoteProgress::processingStage)
                .contains("TRANSCRIBING", "SAVING_TRANSCRIPT", "SUMMARIZING", "SAVING_NOTE", "COMPLETE");
    }

    @Test
    void failedSummaryKeepsPartialProgressAndRetryStartsANewRunUsingStoredTranscript() throws Exception {
        when(repository.findById(note.id)).thenReturn(Optional.of(note));
        when(transcripts.get(note.id)).thenReturn(List.of(new TranscriptSegment(0, "1", "안녕", 0, 1000)));
        when(exaone.summarize(anyList(), any())).thenAnswer(call -> {
            ((IntConsumer) call.getArgument(1)).accept(50);
            throw new IOException("summary offline");
        }).thenReturn("새 요약");
        assertThatThrownBy(() -> service.process(note.id)).isInstanceOf(IOException.class);
        assertThat(service.progress(note.id).status()).isEqualTo("FAILED");
        assertThat(service.progress(note.id).progressPercent()).isEqualTo(87);
        assertThat(service.process(note.id).progressPercent()).isEqualTo(100);
        verifyNoInteractions(google);
    }

    @Test
    void readsPersistedProgressWithoutLoadingTheTranscriptAndNormalizesLegacyReadyNotes() {
        note.status = "READY";
        when(repository.findById(note.id)).thenReturn(Optional.of(note));
        assertThat(service.progress(note.id).progressPercent()).isEqualTo(100);
        assertThat(service.progress(note.id).processingStage()).isEqualTo("COMPLETE");
        verifyNoInteractions(transcripts, google, exaone);
    }
}
