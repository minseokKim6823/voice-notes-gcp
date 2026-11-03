package dev.voicenotes.api;

import dev.voicenotes.application.*;
import dev.voicenotes.domain.*;
import dev.voicenotes.persistence.*;
import dev.voicenotes.infrastructure.*;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notes")
@OpenAPIDefinition(info = @Info(
        title = "보이스 노트 API",
        version = "v1",
        description = "음성 파일을 노트로 저장하고, 한국어 전사·화자 구분·AI 요약을 제공하는 API입니다."
))
@Tag(name = "음성 노트", description = "음성 노트 업로드, 전사·요약 처리, 조회와 편집 API")
public class NoteController {
    private final NoteService notes;

    public NoteController(NoteService notes) { this.notes = notes; }

    @GetMapping
    @Operation(summary = "노트 목록 조회", description = "저장된 노트를 최신순으로 조회합니다. 전사 본문은 상세 조회에서 확인할 수 있습니다.")
    public List<Note> list() throws IOException { return notes.list(); }

    @GetMapping("/search")
    @Operation(summary = "전사 내용으로 노트 검색", description = "전사 본문에 검색어가 포함된 노트를 찾습니다. 한글 부분 문자열 검색을 지원합니다.")
    public List<Note> search(@RequestParam String q) throws IOException { return notes.search(q); }

    @GetMapping("/{id}")
    @Operation(summary = "노트 상세 조회", description = "노트의 요약, 처리 상태, 화자 목록과 전체 전사를 조회합니다.")
    public Note get(@PathVariable String id) throws IOException { return notes.get(id); }

    @GetMapping("/{id}/progress")
    @Operation(summary = "노트 처리 진행률 조회", description = "전사·요약 작업의 상태, 진행 단계, 진행률과 오류를 조회합니다.")
    public ResponseEntity<NoteProgress> progress(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(notes.progress(id));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "음성 노트 업로드", description = "음성 파일과 선택적 제목으로 노트를 생성합니다. WebM, WAV, MP3, FLAC, OGG, M4A/MP4를 지원하며 최대 24MB입니다.")
    public Note create(@RequestParam(required = false) String title, @RequestParam MultipartFile file) throws IOException {
        return notes.create(title, file);
    }

    @PostMapping("/{id}/process")
    @Operation(summary = "음성 전사 및 요약 처리", description = "음성을 한국어로 전사하고 화자를 구분한 뒤 AI 요약을 생성합니다. 이미 저장된 전사는 재사용합니다.")
    public Note process(@PathVariable String id) throws IOException { return notes.process(id); }

    @PostMapping("/{id}/transcribe")
    @Operation(summary = "음성 전사만 실행", description = "선택한 전사 엔진으로 음성을 전사해 저장합니다. AI 요약 API는 호출하지 않습니다.")
    public Note transcribe(@PathVariable String id) throws IOException { return notes.transcribeOnly(id); }

    public record SpeakerNameRequest(String name) {}
    public record MergeSpeakersRequest(String targetSpeaker) {}
    public record AssignSpeakerRequest(String speaker, String name) {}
    public record SegmentTextRequest(String text) {}

    @PatchMapping("/{id}/speakers/{speaker}")
    @Operation(summary = "화자 이름 변경", description = "노트 안에서 해당 화자의 모든 발언에 표시할 이름을 지정합니다.")
    public Note renameSpeaker(@PathVariable String id, @PathVariable String speaker,
                             @RequestBody SpeakerNameRequest request) throws IOException {
        return notes.renameSpeaker(id, speaker, request.name());
    }

    @PostMapping("/{id}/speakers/{speaker}/merge")
    @Operation(summary = "화자 발언 병합", description = "원본 화자의 모든 발언을 지정한 대상 화자로 이동합니다.")
    public Note mergeSpeakers(@PathVariable String id, @PathVariable String speaker,
                             @RequestBody MergeSpeakersRequest request) throws IOException {
        return notes.mergeSpeakers(id, speaker, request.targetSpeaker());
    }

    @PatchMapping("/{id}/segments/{sequence}/speaker")
    @Operation(summary = "발언의 화자 변경", description = "발언 하나를 기존 화자에 배정하거나 새 화자를 만들어 배정합니다. 기존 화자 ID 또는 새 이름 중 하나를 보냅니다.")
    public Note assignSpeaker(@PathVariable String id, @PathVariable int sequence,
                             @RequestBody AssignSpeakerRequest request) throws IOException {
        return notes.assignSpeaker(id, sequence, request.speaker(), request.name());
    }

    @PatchMapping("/{id}/segments/{sequence}")
    @Operation(summary = "전사 발언 수정", description = "발언의 전사 문장을 수정합니다. 실제 변경이 있으면 기존 요약을 갱신해야 한다고 표시합니다.")
    public Note editSegment(@PathVariable String id, @PathVariable int sequence,
                            @RequestBody SegmentTextRequest request) throws IOException {
        return notes.editSegment(id, sequence, request.text());
    }

    @PostMapping("/{id}/summary")
    @Operation(summary = "요약 다시 생성", description = "수정된 화자와 전사를 사용해 요약만 다시 생성합니다. Google 음성 전사는 다시 요청하지 않습니다.")
    public Note regenerateSummary(@PathVariable String id) throws IOException { return notes.regenerateSummary(id); }

    @GetMapping("/{id}/audio")
    @Operation(summary = "원본 음성 재생", description = "업로드된 원본 음성을 브라우저에서 재생할 수 있도록 반환합니다.")
    public ResponseEntity<Resource> audio(@PathVariable String id) throws IOException {
        Note note = notes.get(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(note.contentType()))
                .header("Content-Disposition", "inline; filename=recording")
                .body(new ByteArrayResource(notes.audio(id)));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> upstream(IOException e) {
        return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge() {
        return ResponseEntity.status(413).body(Map.of("error", "음성은 24MB 이하만 사용할 수 있습니다."));
    }
}
