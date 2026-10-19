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
    private final MongoTranscriptStore transcripts;
    private final ExaoneSummarizer exaone;
    private final NoteRepository repository;

    public NoteService(GoogleGateway google, MongoTranscriptStore transcripts,
            ExaoneSummarizer exaone, NoteRepository repository) {
        this.google = google;
        this.transcripts = transcripts;
        this.exaone = exaone;
        this.repository = repository;
    }

    private NoteEntity entity(String id) {
        if (!id.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("잘못된 노트 ID입니다.");
        return repository.findById(id).orElseThrow(() -> new IllegalArgumentException("노트를 찾을 수 없습니다."));
    }

    private Note view(NoteEntity entity) throws IOException {
        return Note.from(entity, transcripts.get(entity.id));
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

    public NoteProgress progress(String id) { return NoteProgress.from(entity(id)); }

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
        for (String id : transcripts.searchNoteIds(query.trim())) {
            repository.findById(id).ifPresent(entity -> notes.add(Note.from(entity, List.of())));
        }
        return notes;
    }

    public synchronized Note process(String id) throws IOException {
        NoteEntity note = entity(id);
        if ("READY".equals(note.status) && !note.summaryStale) return view(note);
        return summarize(note, false);
    }

    public synchronized Note regenerateSummary(String id) throws IOException {
        NoteEntity note = entity(id);
        requireEditable(note);
        return summarize(note, true);
    }

    private Note summarize(NoteEntity note, boolean summaryOnly) throws IOException {
        List<TranscriptSegment> segments = summaryOnly ? transcripts.get(note.id) : null;
        if (summaryOnly && segments.isEmpty()) throw new IllegalStateException("전사 완료 후 요약을 생성할 수 있습니다.");
        note.status = "PROCESSING";
        note.error = "";
        note.progressPercent = 0;
        note.processingStage = "PREPARING";
        if (summaryOnly) note.summaryStale = true;
        repository.save(note);
        try {
            if (segments == null) segments = transcripts.get(note.id);
            if (segments.isEmpty()) {
                updateProgress(note, "TRANSCRIBING", 5);
                segments = google.transcribe(note.audioObject, percent -> updateProgress(note, "TRANSCRIBING", 5 + percent * 70 / 100));
                if (segments.isEmpty()) throw new IOException("전사 결과가 비어 있습니다.");
                updateProgress(note, "SAVING_TRANSCRIPT", 78);
                transcripts.save(note.id, segments);
            }
            updateProgress(note, "SUMMARIZING", 80);
            note.summary = exaone.summarize(segments, percent -> updateProgress(note, "SUMMARIZING", 80 + percent * 15 / 100));
            updateProgress(note, "SAVING_NOTE", 98);
            note.summaryStale = false;
            note.status = "READY";
            note.processingStage = "COMPLETE";
            note.progressPercent = 100;
            repository.save(note);
            return Note.from(note, segments);
        } catch (IOException | RuntimeException e) {
            note.status = "FAILED";
            note.processingStage = "FAILED";
            note.progressPercent = Math.min(note.progressPercent, 99);
            note.error = e.getMessage();
            repository.save(note);
            throw e;
        }
    }

    private void updateProgress(NoteEntity note, String stage, int percent) {
        int next = Math.max(note.progressPercent, Math.max(0, Math.min(99, percent)));
        if (stage.equals(note.processingStage) && next == note.progressPercent) return;
        note.processingStage = stage;
        note.progressPercent = next;
        repository.save(note);
    }

    public synchronized Note renameSpeaker(String id, String speakerId, String name) throws IOException {
        String cleanName = speakerName(name);
        NoteEntity note = entity(id);
        requireEditable(note);
        List<TranscriptSegment> segments = transcripts.get(id);
        requireSpeaker(segments, speakerId);
        return saveSpeakers(note, segments, segments.stream().map(segment -> segment.speaker().equals(speakerId)
                ? segment.withSpeaker(speakerId, cleanName) : segment).toList());
    }

    public synchronized Note mergeSpeakers(String id, String speakerId, String targetId) throws IOException {
        NoteEntity note = entity(id);
        requireEditable(note);
        List<TranscriptSegment> segments = transcripts.get(id);
        requireSpeaker(segments, speakerId);
        TranscriptSegment target = requireSpeaker(segments, targetId);
        if (speakerId.equals(targetId)) throw new IllegalArgumentException("서로 다른 화자를 선택하세요.");
        return saveSpeakers(note, segments, segments.stream().map(segment -> segment.speaker().equals(speakerId)
                ? segment.withSpeaker(targetId, target.speakerName()) : segment).toList());
    }

    public synchronized Note assignSpeaker(String id, int sequence, String speakerId, String name) throws IOException {
        // A new name creates a separate speaker for this utterance; an ID moves it to an existing speaker.
        boolean existing = speakerId != null && !speakerId.isBlank();
        boolean create = name != null && !name.isBlank();
        if (existing == create) throw new IllegalArgumentException("기존 화자 또는 새 화자 이름 중 하나를 입력하세요.");
        String cleanName = create ? speakerName(name) : "";
        NoteEntity note = entity(id);
        requireEditable(note);
        List<TranscriptSegment> segments = transcripts.get(id);
        if (segments.stream().noneMatch(segment -> segment.sequence() == sequence)) {
            throw new IllegalArgumentException("발언 구간을 찾을 수 없습니다.");
        }
        TranscriptSegment target = existing ? requireSpeaker(segments, speakerId) : null;
        String targetId = existing ? target.speaker() : "custom-" + UUID.randomUUID();
        String targetName = existing ? target.speakerName() : cleanName;
        return saveSpeakers(note, segments, segments.stream().map(segment -> segment.sequence() == sequence
                ? segment.withSpeaker(targetId, targetName) : segment).toList());
    }

    public synchronized Note editSegment(String id, int sequence, String text) throws IOException {
        if (text == null || text.isBlank() || text.strip().length() > 5000) {
            throw new IllegalArgumentException("발언 내용은 1자 이상 5,000자 이하로 입력하세요.");
        }
        NoteEntity note = entity(id);
        requireEditable(note);
        List<TranscriptSegment> segments = transcripts.get(id);
        if (segments.stream().noneMatch(segment -> segment.sequence() == sequence)) {
            throw new IllegalArgumentException("발언 구간을 찾을 수 없습니다.");
        }
        return saveSpeakers(note, segments, segments.stream().map(segment -> segment.sequence() == sequence
                ? new TranscriptSegment(segment.sequence(), segment.speaker(), text.strip(), segment.startMs(),
                segment.endMs(), segment.speakerName()) : segment).toList());
    }

    private static void requireEditable(NoteEntity note) {
        if ("PROCESSING".equals(note.status)) throw new IllegalStateException("처리 중에는 화자를 편집할 수 없습니다.");
    }

    private static TranscriptSegment requireSpeaker(List<TranscriptSegment> segments, String speakerId) {
        return segments.stream().filter(segment -> segment.speaker().equals(speakerId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("화자를 찾을 수 없습니다."));
    }

    private static String speakerName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("화자 이름을 입력하세요.");
        String clean = name.strip();
        if (clean.length() > 60 || clean.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("화자 이름은 줄바꿈 없이 60자 이하로 입력하세요.");
        }
        return clean;
    }

    private Note saveSpeakers(NoteEntity note, List<TranscriptSegment> before, List<TranscriptSegment> after) throws IOException {
        if (before.equals(after)) return Note.from(note, before);
        // Mark the summary stale before saving so storage failures cannot make it appear current.
        note.summaryStale = true;
        repository.save(note);
        transcripts.save(note.id, after);
        return Note.from(note, after);
    }

    public byte[] audio(String id) throws IOException { return google.get(entity(id).audioObject); }
}
