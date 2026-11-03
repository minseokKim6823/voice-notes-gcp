package dev.voicenotes;

import dev.voicenotes.api.*;
import dev.voicenotes.application.*;
import dev.voicenotes.domain.*;
import dev.voicenotes.persistence.*;
import dev.voicenotes.infrastructure.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WhisperWorkflowTest {
    private final GoogleGateway google = mock(GoogleGateway.class);
    private final WhisperApiClient whisper = mock(WhisperApiClient.class);
    private final MongoTranscriptStore transcripts = mock(MongoTranscriptStore.class);
    private final ExaoneSummarizer exaone = mock(ExaoneSummarizer.class);
    private final NoteRepository repository = mock(NoteRepository.class);
    private final ProcessingAnalytics analytics = new ProcessingAnalytics(mock(ClickHouseAnalytics.class));
    private final NoteService notes = new NoteService(google, transcripts, exaone, repository,
            new SpeechTranscriber(google, whisper, "whisper", "chirp_3", analytics), analytics);

    @Test
    void processesRecordedAudioThroughWhisperAndReusesSavedTranscriptOnSummaryRetry() throws Exception {
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        byte[] audio = {1, 2, 3};
        Note created = notes.create("회의 녹음", new MockMultipartFile("file", "recording.m4a", "audio/mp4;codecs=mp4a.40.2", audio));
        var saved = org.mockito.ArgumentCaptor.forClass(NoteEntity.class);
        verify(repository).save(saved.capture());
        var entity = saved.getValue();
        when(repository.findById(created.id())).thenReturn(Optional.of(entity));
        when(google.get(entity.audioObject)).thenReturn(audio);
        var segments = List.of(new TranscriptSegment(0, "미확인", "금요일 출시합니다.", 100, 1500));
        when(whisper.transcribe(audio, "audio/mp4")).thenReturn(segments);
        when(transcripts.get(created.id())).thenReturn(List.of(), segments);
        when(exaone.summarize(anyList(), any())).thenThrow(new IOException("summary unavailable")).thenReturn("금요일 출시");
        assertThatThrownBy(() -> notes.process(created.id())).hasMessage("summary unavailable");
        assertThat(entity.status).isEqualTo("FAILED");
        verify(transcripts).save(created.id(), segments);
        assertThat(notes.process(created.id()).status()).isEqualTo("READY");
        verify(whisper, times(1)).transcribe(audio, "audio/mp4");
        verify(google, never()).transcribe(anyString(), any());
        verify(google).put(entity.audioObject, audio, "audio/mp4");
    }

    @Test
    void validatesWhisperBeforeSavingAudio() {
        doThrow(new IllegalStateException("OPENAI_API_KEY missing")).when(whisper).validateConfiguration();
        assertThatThrownBy(() -> notes.create("녹음", new MockMultipartFile("file", "a.webm", "audio/webm", new byte[]{1})))
                .hasMessageContaining("OPENAI_API_KEY");
        verifyNoInteractions(google, repository);
    }

    @Test
    void rejectsOversizedAndUnknownAudioBeforeStorage() {
        assertThatThrownBy(() -> notes.create("녹음", new MockMultipartFile("file", "a.webm", "audio/webm",
                new byte[WhisperApiClient.MAX_AUDIO_BYTES + 1]))).hasMessageContaining("24MB");
        assertThatThrownBy(() -> notes.create("녹음", new MockMultipartFile("file", "a", null, new byte[]{1})))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(google, repository, whisper);
    }
}
