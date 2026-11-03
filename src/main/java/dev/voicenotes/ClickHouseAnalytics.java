package dev.voicenotes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, best-effort telemetry. Never performs network I/O on the note processing thread. */
@Component
public class ClickHouseAnalytics {
    private static final Logger log = LoggerFactory.getLogger(ClickHouseAnalytics.class);
    private final ObjectMapper json;
    private final boolean enabled;
    private final URI endpoint;
    private final String username;
    private final String password;
    private final Duration timeout;
    private final HttpClient http;
    private final ArrayBlockingQueue<ProcessingAnalytics.Event> queue = new ArrayBlockingQueue<>(10_000);
    private final AtomicLong dropped = new AtomicLong();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "clickhouse-analytics");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean initialized;

    public ClickHouseAnalytics(ObjectMapper json,
            @Value("${voice-notes.analytics.enabled:true}") boolean enabled,
            @Value("${voice-notes.analytics.url:http://localhost:8123}") String url,
            @Value("${voice-notes.analytics.database:voice_notes}") String database,
            @Value("${voice-notes.analytics.username:voice_notes}") String username,
            @Value("${voice-notes.analytics.password:local-analytics-only}") String password,
            @Value("${voice-notes.analytics.timeout:3s}") Duration timeout) {
        this.json = json;
        this.enabled = enabled;
        URI base = URI.create(url.trim().replaceAll("/+$", ""));
        if (!List.of("http", "https").contains(base.getScheme()) || base.getHost() == null
                || base.getQuery() != null || base.getFragment() != null || base.getUserInfo() != null
                || !database.matches("[a-zA-Z_][a-zA-Z0-9_]*") || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("ClickHouse 주소, 데이터베이스 이름, 시간 제한을 확인하세요.");
        }
        this.endpoint = URI.create(base + "/?database=" + database
                + "&date_time_input_format=best_effort&output_format_json_quote_64bit_integers=0"
                + "&wait_end_of_query=1");
        this.username = username;
        this.password = password;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @PostConstruct void start() {
        if (enabled) worker.scheduleWithFixedDelay(this::flush, 5, 5, TimeUnit.SECONDS);
    }

    public void record(ProcessingAnalytics.Event event) {
        if (enabled && !queue.offer(event)) dropped.incrementAndGet();
    }

    // No automatic replay after ambiguous HTTP errors: it could double count accepted events.
    synchronized void flush() {
        if (!enabled || queue.isEmpty()) return;
        List<ProcessingAnalytics.Event> batch = new ArrayList<>();
        queue.drainTo(batch, 500);
        try {
            ensureSchema();
            StringBuilder body = new StringBuilder("INSERT INTO processing_events FORMAT JSONEachRow\n");
            for (var event : batch) body.append(json.writeValueAsString(event)).append('\n');
            execute(body.toString(), "");
        } catch (Exception error) {
            dropped.addAndGet(batch.size());
            log.warn("ClickHouse analytics batch was not confirmed ({} events, {}). Notes are unaffected.",
                    batch.size(), error.getClass().getSimpleName());
        }
    }

    private void ensureSchema() throws IOException {
        if (initialized) return;
        try (var input = new ClassPathResource("db/schema-clickhouse.sql").getInputStream()) {
            execute(new String(input.readAllBytes(), StandardCharsets.UTF_8), "");
            initialized = true;
        }
    }

    private String execute(String sql, String parameters) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + parameters)).timeout(timeout)
                .header("X-ClickHouse-User", username).header("X-ClickHouse-Key", password)
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8)).build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IOException("ClickHouse HTTP " + response.statusCode());
            return response.body();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("ClickHouse request interrupted", error);
        }
    }

    public record Snapshot(boolean enabled, boolean available, int days, int pendingEvents, long droppedEvents,
                           JsonNode groups, JsonNode daily, JsonNode recentFailures) {}

    public Snapshot snapshot(int days) {
        if (days < 1 || days > 90) throw new IllegalArgumentException("조회 기간은 1~90일이어야 합니다.");
        if (!enabled) return empty(false, days);
        try {
            ensureSchema();
            String range = " FROM processing_events AS events WHERE events.occurred_at >= now64(3) - toIntervalDay({days:UInt16}) ";
            JsonNode groups = query("""
                    SELECT stage, action, provider, model, count() AS calls,
                        countIf(success = 1) AS succeeded, countIf(success = 0) AS failed,
                        round(avg(duration_ms), 1) AS avg_ms,
                        quantile(0.95)(duration_ms) AS p95_ms,
                        sum(ifNull(events.input_tokens, 0)) AS input_tokens,
                        sum(ifNull(events.output_tokens, 0)) AS output_tokens,
                        countIf(events.input_tokens IS NOT NULL) AS input_usage_calls,
                        countIf(events.output_tokens IS NOT NULL) AS output_usage_calls,
                        countIf(events.input_tokens IS NOT NULL OR events.output_tokens IS NOT NULL) AS token_calls,
                        countIf(events.input_tokens IS NOT NULL AND events.output_tokens IS NOT NULL) AS usage_calls
                    """ + range + " GROUP BY stage, action, provider, model ORDER BY stage, action, provider, model", days);
            JsonNode daily = query("""
                    SELECT toString(toDate(occurred_at, 'UTC')) AS date, count() AS attempts,
                        countIf(success = 1) AS succeeded, countIf(success = 0) AS failed
                    """ + range + " AND stage = 'workflow' GROUP BY date ORDER BY date", days);
            JsonNode failures = query("""
                    SELECT toString(events.occurred_at) AS occurred_at, toString(events.note_id) AS note_id,
                        toString(events.attempt_id) AS attempt_id, action, stage, provider, model, error_type
                    """ + range + " AND success = 0 ORDER BY events.occurred_at DESC LIMIT 20", days);
            return new Snapshot(true, true, days, queue.size(), dropped.get(), groups, daily, failures);
        } catch (IOException | RuntimeException error) {
            return empty(false, days);
        }
    }

    private JsonNode query(String sql, int days) throws IOException {
        JsonNode result = json.readTree(execute(sql + " FORMAT JSON", "&param_days="
                + URLEncoder.encode(Integer.toString(days), StandardCharsets.UTF_8)));
        if (result == null || !result.path("data").isArray()) throw new IOException("Invalid analytics response");
        return result.path("data");
    }

    private Snapshot empty(boolean available, int days) {
        return new Snapshot(enabled, available, days, queue.size(), dropped.get(),
                json.createArrayNode(), json.createArrayNode(), json.createArrayNode());
    }

    @PreDestroy void stop() {
        worker.shutdown();
        try { worker.awaitTermination(timeout.toMillis() * 2, TimeUnit.MILLISECONDS); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        // Best effort final batch; a process crash or backlog can lose queued telemetry.
        flush();
    }
}
