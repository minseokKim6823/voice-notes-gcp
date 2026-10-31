package dev.voicenotes.domain;

import dev.voicenotes.api.*;
import dev.voicenotes.application.*;
import dev.voicenotes.persistence.*;
import dev.voicenotes.infrastructure.*;

public record NoteProgress(String id, String status, int progressPercent, String processingStage, String error) {
    public static NoteProgress from(NoteEntity note) {
        return new NoteProgress(note.id, note.status, "READY".equals(note.status) ? 100 : note.progressPercent,
                "READY".equals(note.status) ? "COMPLETE" : note.processingStage, note.error);
    }
}
