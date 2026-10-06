package dev.voicenotes;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NoteRepository extends JpaRepository<NoteEntity, String> {
    List<NoteEntity> findAllByOrderByCreatedAtDesc();
}
