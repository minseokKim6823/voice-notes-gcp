package dev.voicenotes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "voice_notes")
public class NoteEntity {
    @Id public String id;
    @Column(nullable = false) public String title;
    @Column(nullable = false) public String fileName;
    @Column(nullable = false) public String audioObject;
    @Column(nullable = false) public String contentType;
    @Column(nullable = false) public Instant createdAt;
    @Column(nullable = false) public String status;
    @Column(columnDefinition = "longtext") public String summary;
    @Column(columnDefinition = "text") public String error;
    @Column(nullable = false) public boolean summaryStale;
    @Column(nullable = false) public int progressPercent;
    @Column(nullable = false) public String processingStage = "UPLOADED";

    protected NoteEntity() {}

    public NoteEntity(String id, String title, String fileName, String audioObject, String contentType) {
        this.id = id;
        this.title = title;
        this.fileName = fileName;
        this.audioObject = audioObject;
        this.contentType = contentType;
        this.createdAt = Instant.now();
        this.status = "UPLOADED";
        this.summary = "";
        this.error = "";
    }
}
