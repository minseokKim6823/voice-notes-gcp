package dev.voicenotes.persistence;

import dev.voicenotes.api.*;
import dev.voicenotes.application.*;
import dev.voicenotes.domain.*;
import dev.voicenotes.infrastructure.*;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NoteRepository extends JpaRepository<NoteEntity, String> {
    List<NoteEntity> findAllByOrderByCreatedAtDesc();
}
