package dev.voicenotes.application;

import dev.voicenotes.domain.Note;
import dev.voicenotes.domain.NoteEntity;
import dev.voicenotes.domain.TranscriptSegment;
import dev.voicenotes.infrastructure.ClickHouseAnalytics;
import dev.voicenotes.infrastructure.GoogleGateway;
import dev.voicenotes.infrastructure.SpeechTranscriber;
import dev.voicenotes.persistence.MongoTranscriptStore;
import dev.voicenotes.persistence.NoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NoteProcessingWorkflowTest {
    private final GoogleGateway google = mock(GoogleGateway.class);
    private final SpeechTranscriber speech = mock(SpeechTranscriber.class);
    private final MongoTranscriptStore transcripts = mock(MongoTranscriptStore.class);
    private final TranscriptSummarizer summary = mock(TranscriptSummarizer.class);
    private final NoteRepository repository = mock(NoteRepository.class);
    private final NoteService service = new NoteService(google, transcripts, summary, repository, speech,
            new ProcessingAnalytics(mock(ClickHouseAnalytics.class)));
    private final NoteEntity entity = new NoteEntity(UUID.randomUUID().toString(), "회의", "a.wav", "audio/a.wav", "audio/wav");

    @BeforeEach
    void storedNote() throws IOException {
        when(repository.findById(entity.id)).thenReturn(Optional.of(entity));
        when(transcripts.get(entity.id)).thenReturn(List.of());
    }

    private Note process(boolean includeSummary) throws IOException {
        return includeSummary ? service.process(entity.id) : service.transcribeOnly(entity.id);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transcriptionFailureKeepsProgressAndMakesBothModesRetryable(boolean includeSummary) throws Exception {
        when(speech.transcribe(anyString(), anyString(), any())).thenAnswer(call -> {
            ((IntConsumer) call.getArgument(2)).accept(50);
            throw new IOException("speech unavailable");
        });
        assertThatThrownBy(() -> process(includeSummary)).isInstanceOf(IOException.class).hasMessage("speech unavailable");
        var progress = service.progress(entity.id);
        assertThat(progress.status()).isEqualTo("FAILED");
        assertThat(progress.processingStage()).isEqualTo("FAILED");
        assertThat(progress.progressPercent()).isBetween(1, 99);
        assertThat(progress.error()).isEqualTo("speech unavailable");
        verify(transcripts, never()).save(anyString(), anyList());
        verifyNoInteractions(summary);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cachedTranscriptSkipsSpeechAndClearsThePreviousFailure(boolean includeSummary) throws Exception {
        entity.status = "FAILED";
        entity.error = "old failure";
        var cached = List.of(new TranscriptSegment(0, "1", "금요일 출시", 0, 1500));
        when(transcripts.get(entity.id)).thenReturn(cached);
        when(summary.summarize(anyList(), any())).thenReturn("저장한 전사의 요약");
        var note = process(includeSummary);
        assertThat(note.status()).isEqualTo(includeSummary ? "READY" : "TRANSCRIBED");
        assertThat(note.processingStage()).isEqualTo(includeSummary ? "COMPLETE" : "TRANSCRIPTION_COMPLETE");
        assertThat(note.progressPercent()).isEqualTo(100);
        assertThat(note.error()).isEmpty();
        assertThat(note.segments()).containsExactlyElementsOf(cached);
        verifyNoInteractions(speech, google);
        verify(transcripts, never()).save(anyString(), anyList());
        if (includeSummary) verify(summary).summarize(eq(cached), any());
        else verifyNoInteractions(summary);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptySpeechResultsFailBeforeTranscriptStorageOrSummary(boolean includeSummary) throws Exception {
        when(speech.transcribe(anyString(), anyString(), any())).thenReturn(List.of());
        assertThatThrownBy(() -> process(includeSummary)).isInstanceOf(IOException.class).hasMessageContaining("비어");
        assertThat(service.progress(entity.id).status()).isEqualTo("FAILED");
        verify(transcripts, never()).save(anyString(), anyList());
        verifyNoInteractions(summary);
    }

    @Test
    void summaryOnlyWithoutATranscriptDoesNotEnterProcessingOrInvokeProviders() {
        assertThatThrownBy(() -> service.regenerateSummary(entity.id)).isInstanceOf(IllegalStateException.class);
        assertThat(entity.status).isEqualTo("UPLOADED");
        verify(repository, never()).save(any());
        verifyNoInteractions(speech, summary, google);
    }
}
