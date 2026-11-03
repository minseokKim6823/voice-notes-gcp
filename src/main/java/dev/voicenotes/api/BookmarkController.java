package dev.voicenotes.api;

import dev.voicenotes.application.BookmarkService;
import dev.voicenotes.domain.NoteBookmark;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notes/{noteId}/bookmarks")
public class BookmarkController {
    private final BookmarkService service;
    public BookmarkController(BookmarkService service) { this.service = service; }
    public record Request(long positionMs, String label) {}
    @GetMapping
    public ResponseEntity<List<NoteBookmark>> list(@PathVariable String noteId) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(noteId));
    }
    @PutMapping("/{id}")
    public NoteBookmark put(@PathVariable String noteId, @PathVariable String id, @RequestBody Request body) throws IOException {
        return service.put(noteId, id, body.positionMs(), body.label());
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String noteId, @PathVariable String id) throws IOException {
        service.delete(noteId, id);
        return ResponseEntity.noContent().build();
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> unavailable(IOException e) {
        return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
    }
}
