package dev.voicenotes;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MongoTranscriptStoreTest {
    @Test
    void mapsLegacyDocumentsWithoutSpeakerNamesAndKeepsTheNoteIdAndOffsets() {
        var converter = new org.springframework.data.mongodb.core.convert.MappingMongoConverter(
                org.springframework.data.mongodb.core.convert.NoOpDbRefResolver.INSTANCE,
                new org.springframework.data.mongodb.core.mapping.MongoMappingContext());
        converter.afterPropertiesSet();
        var source = org.bson.Document.parse("""
                {"_id":"11111111-1111-4111-8111-111111111111","segments":[
                  {"sequence":0,"speaker":"1","text":"기존 발언","startMs":1250,"endMs":4000}]}
                """);
        var transcript = converter.read(MongoTranscriptStore.TranscriptDocument.class, source);
        assertThat(transcript.id()).isEqualTo("11111111-1111-4111-8111-111111111111");
        assertThat(transcript.segments()).containsExactly(new TranscriptSegment(0, "1", "기존 발언", 1250, 4000));
        var roundtrip = new org.bson.Document();
        converter.write(transcript, roundtrip);
        assertThat(roundtrip.getString("_id")).isEqualTo(transcript.id());
    }

    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final MongoTranscriptStore store = new MongoTranscriptStore(mongo);

    @Test
    void replacesTheWholeNoteWithSpeakerNamesAndOffsetsInOneWrite() throws Exception {
        var segments = List.of(new TranscriptSegment(7, "custom-123", "수정된 전사 👍", 1250, 4000, "김민수"));
        store.save("note", segments);
        verify(mongo).save(new MongoTranscriptStore.TranscriptDocument("note", segments), MongoTranscriptStore.COLLECTION);
        verifyNoMoreInteractions(mongo);
    }

    @Test
    void returnsAllSegmentsInSequenceOrderAndHandlesMissingNotes() throws Exception {
        var first = new TranscriptSegment(0, "1", "안녕", 0, 1000, "민수");
        var last = new TranscriptSegment(10001, "2", "네", 1000, 1500);
        when(mongo.findById("note", MongoTranscriptStore.TranscriptDocument.class, MongoTranscriptStore.COLLECTION))
                .thenReturn(new MongoTranscriptStore.TranscriptDocument("note", List.of(last, first)));
        assertThat(store.get("note")).containsExactly(first, last);
        assertThat(store.get("missing")).isEmpty();
    }

    @Test
    void searchesKoreanSubstringsAndTreatsRegexCharactersAsLiteralText() throws Exception {
        when(mongo.find(any(Query.class), eq(MongoTranscriptStore.TranscriptDocument.class), eq(MongoTranscriptStore.COLLECTION)))
                .thenReturn(List.of(new MongoTranscriptStore.TranscriptDocument("note", null)));
        assertThat(store.searchNoteIds("  목요일.*(완료)  ")).containsExactly("note");
        var query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(query.capture(), eq(MongoTranscriptStore.TranscriptDocument.class), eq(MongoTranscriptStore.COLLECTION));
        var expression = (Pattern) query.getValue().getQueryObject().get("segments.text");
        assertThat(expression.matcher("이번 목요일.*(완료)입니다").find()).isTrue();
        assertThat(expression.matcher("이번 목요일까지 완료입니다").find()).isFalse();
        assertThat(store.searchNoteIds(" ")).isEmpty();
    }

    @Test
    void convertsDatabaseFailuresToApiIoErrors() {
        when(mongo.save(any(), anyString())).thenThrow(new DataAccessResourceFailureException("offline"));
        assertThatThrownBy(() -> store.save("note", List.of())).isInstanceOf(IOException.class).hasMessageContaining("MongoDB 저장");
    }
}
