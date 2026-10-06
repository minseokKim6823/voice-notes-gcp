package dev.voicenotes;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notes")
public class NoteController {
    private final NoteService notes;

    public NoteController(NoteService notes) { this.notes = notes; }

    @GetMapping
    public List<Note> list() throws IOException { return notes.list(); }

    @GetMapping("/search")
    public List<Note> search(@RequestParam String q) throws IOException { return notes.search(q); }

    @GetMapping("/{id}")
    public Note get(@PathVariable String id) throws IOException { return notes.get(id); }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Note create(@RequestParam(required = false) String title, @RequestParam MultipartFile file) throws IOException {
        return notes.create(title, file);
    }

    @PostMapping("/{id}/process")
    public Note process(@PathVariable String id) throws IOException { return notes.process(id); }

    @GetMapping("/{id}/audio")
    public ResponseEntity<byte[]> audio(@PathVariable String id) throws IOException {
        Note note = notes.get(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(note.contentType()))
                .header("Content-Disposition", "inline; filename=recording")
                .body(notes.audio(id));
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
        return ResponseEntity.status(413).body(Map.of("error", "음성 파일은 30MB 이하만 업로드할 수 있습니다."));
    }
}
