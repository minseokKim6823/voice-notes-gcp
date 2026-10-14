package dev.voicenotes;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SpeakerWorkflowTest {
    private final String id = UUID.randomUUID().toString();
    private final GoogleGateway google = mock(GoogleGateway.class);
    private final ElasticsearchGateway elasticsearch = mock(ElasticsearchGateway.class);
    private final ExaoneSummarizer exaone = mock(ExaoneSummarizer.class);
    private final NoteRepository repository = mock(NoteRepository.class);
    private final NoteService notes = new NoteService(google, elasticsearch, exaone, repository);
    private final AtomicReference<List<TranscriptSegment>> stored = new AtomicReference<>();
    private NoteEntity entity;
    private MockMvc mvc;

    @BeforeEach
    void prepareNote() throws Exception {
        entity = new NoteEntity(id, "회의", "meeting.wav", "audio/meeting.wav", "audio/wav");
        entity.status = "READY";
        entity.summary = "화자 1이 일정을 정했습니다.";
        stored.set(List.of(new TranscriptSegment(0, "1", "금요일 출시입니다.", 0, 1500),
                new TranscriptSegment(1, "2", "테스트하겠습니다.", 1600, 3000),
                new TranscriptSegment(2, "1", "감사합니다.", 3500, 4000)));
        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(repository.save(any(NoteEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(elasticsearch.get(id)).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> { stored.set(List.copyOf(invocation.getArgument(1))); return null; })
                .when(elasticsearch).save(eq(id), anyList());
        mvc = MockMvcBuilders.standaloneSetup(new NoteController(notes)).build();
    }

    @Test
    void renamePersistsEveryUtteranceAndInvalidatesSummaryWithoutChangingIdsOrTimes() throws Exception {
        mvc.perform(patch("/api/notes/{id}/speakers/1", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  김민수 👍  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.speakers[0].name").value("김민수 👍"))
                .andExpect(jsonPath("$.speakers[0].segmentCount").value(2))
                .andExpect(jsonPath("$.speakers[0].durationMs").value(2000))
                .andExpect(jsonPath("$.summaryStale").value(true));
        assertThat(notes.get(id).transcript()).contains("김민수 👍: 금요일 출시입니다.", "김민수 👍: 감사합니다.");
        assertThat(stored.get()).containsExactly(new TranscriptSegment(0, "1", "금요일 출시입니다.", 0, 1500, "김민수 👍"),
                new TranscriptSegment(1, "2", "테스트하겠습니다.", 1600, 3000),
                new TranscriptSegment(2, "1", "감사합니다.", 3500, 4000, "김민수 👍"));
        assertThat(entity.summary).isEqualTo("화자 1이 일정을 정했습니다.");
        verifyNoInteractions(google, exaone);
    }

    @Test
    void movesOneUtteranceToAnExistingNamedSpeaker() throws Exception {
        notes.renameSpeaker(id, "2", "지수");
        Note moved = notes.assignSpeaker(id, 0, "2", null);
        assertThat(moved.segments().get(0)).isEqualTo(new TranscriptSegment(0, "2", "금요일 출시입니다.", 0, 1500, "지수"));
        assertThat(moved.speakers()).containsExactly(new Speaker("2", "지수", 2, 2900), new Speaker("1", "화자 1", 1, 500));
        assertThat(moved.segments().get(2).speaker()).isEqualTo("1");
    }

    @Test
    void splitsAnUtteranceIntoANewSpeakerAndCanMergeItBack() throws Exception {
        mvc.perform(patch("/api/notes/{id}/segments/0/speaker", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"새 참가자\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.speakers.length()").value(3));
        String customId = stored.get().get(0).speaker();
        assertThat(customId).startsWith("custom-");
        assertThat(notes.get(id).speakers().get(0).name()).isEqualTo("새 참가자");
        mvc.perform(post("/api/notes/{id}/speakers/{speaker}/merge", id, customId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"targetSpeaker\":\"1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.speakers.length()").value(2));
        assertThat(stored.get().get(0).speaker()).isEqualTo("1");
        assertThat(stored.get().get(0).speakerName()).isEmpty();
    }

    @Test
    void mergingPreservesTargetNameAndRemovesTheSourceFromAllUtterances() throws Exception {
        notes.renameSpeaker(id, "2", "진행자");
        Note merged = notes.mergeSpeakers(id, "1", "2");
        assertThat(merged.speakers()).containsExactly(new Speaker("2", "진행자", 3, 3400));
        assertThat(merged.segments()).extracting(TranscriptSegment::sequence).containsExactly(0, 1, 2);
        assertThat(merged.segments()).extracting(TranscriptSegment::speakerName).containsOnly("진행자");
    }

    @Test
    void transcriptCorrectionKeepsSpeakerAndOffsetsAndStoresSearchableText() throws Exception {
        mvc.perform(patch("/api/notes/{id}/segments/1", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"  목요일까지 검증합니다.  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summaryStale").value(true));
        assertThat(notes.get(id).segments().get(1)).isEqualTo(new TranscriptSegment(1, "2", "목요일까지 검증합니다.", 1600, 3000));
        verify(elasticsearch).save(eq(id), argThat(segments -> segments.get(1).text().equals("목요일까지 검증합니다.")));
    }

    @Test
    void summaryRegenerationUsesEditedNamesAndNeverRepeatsSpeechRecognition() throws Exception {
        notes.renameSpeaker(id, "1", "민수");
        when(exaone.summarize(anyList())).thenReturn("민수가 일정을 정했습니다.");
        mvc.perform(post("/api/notes/{id}/summary", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.summaryStale").value(false))
                .andExpect(jsonPath("$.summary").value("민수가 일정을 정했습니다."));
        verify(exaone).summarize(argThat(segments -> "민수".equals(segments.get(0).displayName())));
        verifyNoInteractions(google);
        assertThat(entity.summaryStale).isFalse();
    }

    @Test
    void processingStaleReadyNoteAlsoRegeneratesSummary() throws Exception {
        notes.renameSpeaker(id, "1", "민수");
        when(exaone.summarize(anyList())).thenReturn("새 요약");
        assertThat(notes.process(id).summaryStale()).isFalse();
        verify(exaone).summarize(anyList());
        verifyNoInteractions(google);
    }

    @Test
    void unchangedEditsAndCurrentReadyNotesDoNotWriteOrCallTheModel() throws Exception {
        notes.assignSpeaker(id, 0, "1", null);
        notes.editSegment(id, 0, "금요일 출시입니다.");
        notes.process(id);
        assertThat(entity.summaryStale).isFalse();
        verify(elasticsearch, never()).save(anyString(), anyList());
        verify(repository, never()).save(any());
        verifyNoInteractions(exaone, google);
    }

    @Test
    void failedSummaryKeepsExistingSummaryAndCorrectionsForRetry() throws Exception {
        notes.renameSpeaker(id, "1", "민수");
        when(exaone.summarize(anyList())).thenThrow(new IOException("요약 API 실패"));
        mvc.perform(post("/api/notes/{id}/summary", id)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("요약 API 실패"));
        assertThat(notes.get(id).status()).isEqualTo("FAILED");
        assertThat(notes.get(id).summary()).isEqualTo("화자 1이 일정을 정했습니다.");
        assertThat(notes.get(id).summaryStale()).isTrue();
        doReturn("민수의 새 요약").when(exaone).summarize(anyList());
        assertThat(notes.process(id).status()).isEqualTo("READY");
        assertThat(notes.get(id).summaryStale()).isFalse();
        verifyNoInteractions(google);
    }

    @Test
    void failedBulkWriteStillMarksThePreviousSummaryStale() throws Exception {
        doThrow(new IOException("bulk failure")).when(elasticsearch).save(eq(id), anyList());
        assertThatThrownBy(() -> notes.renameSpeaker(id, "1", "민수")).isInstanceOf(IOException.class);
        assertThat(entity.summaryStale).isTrue();
        verify(repository).save(entity);
    }

    @Test
    void rejectsBlankLongOrMultilineNamesWithoutWriting() throws Exception {
        for (String name : List.of(" ", "가".repeat(61), "민수\n진행자")) {
            mvc.perform(patch("/api/notes/{id}/speakers/1", id).contentType(MediaType.APPLICATION_JSON)
                            .content(new ObjectMapper().writeValueAsString(new NoteController.SpeakerNameRequest(name))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isString());
        }
        verify(elasticsearch, never()).save(anyString(), anyList());
        assertThat(entity.summaryStale).isFalse();
    }

    @Test
    void rejectsAmbiguousAssignmentsAndMissingTargets() {
        assertThatThrownBy(() -> notes.assignSpeaker(id, 0, "1", "새 화자")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> notes.assignSpeaker(id, 0, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> notes.assignSpeaker(id, 0, "99", null)).hasMessageContaining("화자를 찾을 수 없습니다");
        assertThatThrownBy(() -> notes.assignSpeaker(id, 99, "1", null)).hasMessageContaining("발언 구간");
        assertThatThrownBy(() -> notes.mergeSpeakers(id, "1", "1")).hasMessageContaining("서로 다른");
        assertThatThrownBy(() -> notes.mergeSpeakers(id, "1", null)).hasMessageContaining("화자를 찾을 수 없습니다");
        assertThatThrownBy(() -> notes.renameSpeaker(id, "99", "이름")).hasMessageContaining("화자를 찾을 수 없습니다");
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsInvalidTranscriptCorrections() {
        assertThatThrownBy(() -> notes.editSegment(id, 0, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> notes.editSegment(id, 0, "가".repeat(5001))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> notes.editSegment(id, 99, "수정")).hasMessageContaining("발언 구간");
        verify(repository, never()).save(any());
    }

    @Test
    void blocksEditsAndSummaryRegenerationWhileProcessing() throws Exception {
        entity.status = "PROCESSING";
        mvc.perform(patch("/api/notes/{id}/speakers/1", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"민수\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("처리 중에는 화자를 편집할 수 없습니다."));
        assertThatThrownBy(() -> notes.assignSpeaker(id, 0, "2", null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> notes.mergeSpeakers(id, "1", "2")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> notes.editSegment(id, 0, "수정")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> notes.regenerateSummary(id)).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void requiresTranscriptBeforeGeneratingSummary() {
        stored.set(List.of());
        assertThatThrownBy(() -> notes.regenerateSummary(id)).hasMessageContaining("전사 완료 후");
        verifyNoInteractions(google, exaone);
        verify(repository, never()).save(any());
    }

    @Test
    void handlesUnknownSpeakersAndLegacyDocumentsWithoutTiming() {
        stored.set(List.of(new TranscriptSegment(0, "미확인", "기존 녹취", 0, 0)));
        assertThat(Note.from(entity, stored.get()).speakers()).containsExactly(new Speaker("미확인", "미확인 화자", 1, 0));
    }
}
