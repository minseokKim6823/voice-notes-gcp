package dev.voicenotes;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.function.IntConsumer;

@Component
public class SpeechTranscriber {
    private final GoogleGateway google;
    private final WhisperApiClient whisper;
    private final String provider;
    private final String googleModel;
    private final ProcessingAnalytics analytics;

    public SpeechTranscriber(GoogleGateway google, WhisperApiClient whisper,
            @Value("${voice-notes.stt-provider:whisper}") String provider,
            @Value("${voice-notes.speech-model:chirp_3}") String googleModel, ProcessingAnalytics analytics) {
        if (!List.of("whisper", "google").contains(provider)) {
            throw new IllegalArgumentException("VOICE_NOTES_STT_PROVIDER는 whisper 또는 google이어야 합니다.");
        }
        this.google = google;
        this.whisper = whisper;
        this.provider = provider;
        this.googleModel = googleModel;
        this.analytics = analytics;
    }

    public void validateConfiguration() {
        if (provider.equals("whisper")) whisper.validateConfiguration();
    }

    public List<TranscriptSegment> transcribe(String objectName, String contentType, IntConsumer progress) throws IOException {
        try (var measurement = analytics.stage("transcription", provider, provider.equals("google") ? googleModel : "whisper-1")) {
            try {
                var result = performTranscription(objectName, contentType, progress);
                if (result.isEmpty()) throw new IOException("전사 결과가 비어 있습니다.");
                measurement.success();
                return result;
            } catch (IOException | RuntimeException error) {
                measurement.failure(error);
                throw error;
            }
        }
    }

    private List<TranscriptSegment> performTranscription(String objectName, String contentType, IntConsumer progress) throws IOException {
        if (provider.equals("google")) return google.transcribe(objectName, progress);
        validateConfiguration();
        progress.accept(0);
        var segments = whisper.transcribe(google.get(objectName), contentType);
        // Whisper is a batch API with no intermediate percentage events.
        progress.accept(100);
        return segments;
    }
}
