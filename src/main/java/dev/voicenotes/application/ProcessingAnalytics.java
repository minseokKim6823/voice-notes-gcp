package dev.voicenotes.application;

import dev.voicenotes.infrastructure.ClickHouseAnalytics;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Request-local correlation only: the synchronous processing pipeline stays on one thread. */
@Component
public class ProcessingAnalytics {
    private final ClickHouseAnalytics store;
    private final ThreadLocal<Attempt> current = new ThreadLocal<>();

    public ProcessingAnalytics(ClickHouseAnalytics store) { this.store = store; }

    public Attempt attempt(String noteId, String action) {
        Attempt attempt = new Attempt(noteId, action, current.get());
        current.set(attempt);
        return attempt;
    }

    public Measurement stage(String stage, String provider, String model) {
        return new Measurement(current.get(), stage, provider, model);
    }

    public record Event(String event_id, String attempt_id, String note_id, String occurred_at,
                        String action, String stage, String provider, String model, int success,
                        long duration_ms, Long input_tokens, Long output_tokens, String error_type) {}

    public final class Attempt implements AutoCloseable {
        private final String id = UUID.randomUUID().toString();
        private final String noteId;
        private final String action;
        private final Attempt previous;
        private final Measurement measurement;

        private Attempt(String noteId, String action, Attempt previous) {
            this.noteId = noteId;
            this.action = action;
            this.previous = previous;
            this.measurement = new Measurement(this, "workflow", "", "");
        }

        public void success() { measurement.success(); }
        public void failure(Exception error) { measurement.failure(error); }

        @Override public void close() {
            try { measurement.close(); }
            finally {
                if (previous == null) current.remove();
                else current.set(previous);
            }
        }
    }

    public final class Measurement implements AutoCloseable {
        private final Attempt attempt;
        private final String stage;
        private final String provider;
        private final String model;
        private final long started = System.nanoTime();
        private boolean success;
        private boolean closed;
        private Long inputTokens;
        private Long outputTokens;
        private String errorType = "Incomplete";

        private Measurement(Attempt attempt, String stage, String provider, String model) {
            this.attempt = attempt;
            this.stage = stage;
            this.provider = provider;
            this.model = model;
        }

        public void success() { success = true; errorType = ""; }
        public void failure(Exception error) { errorType = error.getClass().getSimpleName(); }
        public void tokens(Long input, Long output) {
            inputTokens = input != null && input >= 0 ? input : null;
            outputTokens = output != null && output >= 0 ? output : null;
        }

        @Override public void close() {
            if (closed || attempt == null) return;
            closed = true;
            store.record(new Event(UUID.randomUUID().toString(), attempt.id, attempt.noteId,
                    Instant.now().toString(), attempt.action, stage, provider, model, success ? 1 : 0,
                    Math.max(0, (System.nanoTime() - started) / 1_000_000), inputTokens, outputTokens, errorType));
        }
    }
}
