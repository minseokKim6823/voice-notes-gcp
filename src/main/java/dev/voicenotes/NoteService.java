package dev.voicenotes;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class NoteService {
    private static final Set<String> TYPES = Set.of("audio/webm", "audio/wav", "audio/x-wav",
            "audio/mpeg", "audio/mp3", "audio/flac", "audio/ogg");
    private final GoogleGateway google;
    private final ElasticsearchGateway elasticsearch;
    private final ExaoneSummarizer exaone;
    private final NoteRepository repository;

    public NoteService(GoogleGateway google, ElasticsearchGateway elasticsearch,
            ExaoneSummarizer exaone, NoteRepository repository) {
        this.google = google;
        this.elasticsearch = elasticsearch;
        this.exaone = exaone;
        this.repository = repository;
    }

    private NoteEntity entity(String id) {
        if (!id.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("잘못된 노트 ID입니다.");
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("노트를 찾을 수 없습니다."));
    }

    private Note view(NoteEntity entity) throws IOException {
        return Note.from(entity, elasticsearch.get(entity.id));
    }

    public Note create(String title, MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("음성 파일을 선택하세요.");
        String type = file.getContentType();
        if (!TYPES.contains(type)) throw new IllegalArgumentException("webm, wav, mp3, flac, ogg 음성 파일만 업로드할 수 있습니다.");
        String id = UUID.randomUUID().toString();
        String original = file.getOriginalFilename() == null ? "recording" : file.getOriginalFilename();
        String safeName = original.replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        if (safeName.isBlank()) safeName = "recording";
        String cleanTitle = title == null || title.isBlank() ? safeName : title.trim();
        if (cleanTitle.length() > 120) throw new IllegalArgumentException("제목은 120자 이하여야 합니다.");
        String name = "audio/" + id + "/" + safeName;
        google.put(name, file.getBytes(), type);
        NoteEntity note = repository.save(new NoteEntity(id, cleanTitle, safeName, name, type));
        return Note.from(note, List.of());
    }

    public Note get(String id) throws IOException { return view(entity(id)); }

    public List<Note> list() {
        List<Note> notes = new ArrayList<>();
        for (NoteEntity entity : repository.findAllByOrderByCreatedAtDesc()) {
            notes.add(Note.from(entity, List.of()));
        }
        return notes;
    }

    public List<Note> search(String query) throws IOException {
        if (query == null || query.isBlank()) return list();
        List<Note> notes = new ArrayList<>();
        for (String id : elasticsearch.searchNoteIds(query.trim())) {
            repository.findById(id).ifPresent(entity -> notes.add(Note.from(entity, List.of())));
        }
        return notes;
    }

    public synchronized Note process(String id) throws IOException {
        NoteEntity note = entity(id);
        if ("READY".equals(note.status)) return view(note);
        note.status = "PROCESSING";
        note.error = "";
        repository.save(note);
        try {
            List<TranscriptSegment> segments = elasticsearch.get(id);
            if (segments.isEmpty()) {
                segments = google.transcribe(note.audioObject);
                elasticsearch.save(id, segments);
            }
            note.summary = exaone.summarize(segments);
            note.status = "READY";
            repository.save(note);
            return Note.from(note, segments);
        } catch (IOException | RuntimeException e) {
            note.status = "FAILED";
            note.error = e.getMessage();
            repository.save(note);
            throw e;
        }
    }

    public byte[] audio(String id) throws IOException { return google.get(entity(id).audioObject); }
}
