package dev.voicenotes;

import dev.voicenotes.api.NoteController;
import dev.voicenotes.application.NoteService;
import dev.voicenotes.domain.Note;
import dev.voicenotes.domain.NoteEntity;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AudioPlaybackTest {
    @Test
    void audioSupportsByteRangesForSeekingAndPlaybackSynchronization() throws Exception {
        var service = mock(NoteService.class);
        var note = new NoteEntity(UUID.randomUUID().toString(), "회의", "meeting.wav", "audio/test.wav", "audio/wav");
        byte[] audio = new byte[100];
        for (int i = 0; i < audio.length; i++) audio[i] = (byte) i;
        when(service.get(note.id)).thenReturn(Note.from(note, List.of()));
        when(service.audio(note.id)).thenReturn(audio);
        MockMvcBuilders.standaloneSetup(new NoteController(service)).build()
                .perform(get("/api/notes/{id}/audio", note.id).header("Range", "bytes=10-19"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Range", "bytes 10-19/100"))
                .andExpect(content().bytes(Arrays.copyOfRange(audio, 10, 20)));
    }
}
