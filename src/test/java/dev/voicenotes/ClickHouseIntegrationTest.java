package dev.voicenotes.infrastructure;

import dev.voicenotes.api.AnalyticsController;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static dev.voicenotes.infrastructure.ClickHouseAnalyticsTest.event;
import static org.assertj.core.api.Assertions.*;

/** Fixed isolated port only. Creates and removes its own randomly named database. */
@EnabledIfEnvironmentVariable(named = "RUN_STORAGE_TESTS", matches = "true")
class ClickHouseIntegrationTest {
    @Test void createsRealSchemaAndAggregatesDurationsFailuresTokensAndTimeWindows() throws Exception {
        String database = "analytics_test_" + UUID.randomUUID().toString().replace("-", "");
        sql("CREATE DATABASE " + database);
        var store = new ClickHouseAnalytics(new ObjectMapper(), true, "http://127.0.0.1:53319", database,
                "voice_notes_test", "local-test-only", Duration.ofSeconds(5));
        try {
            var empty = store.snapshot(7);
            assertThat(empty.available()).isTrue();
            assertThat(empty.groups()).isEmpty();
            Instant now = Instant.now().minusSeconds(60);
            store.record(event("workflow", "", 1, 1000, null, null, now));
            store.record(event("workflow", "", 0, 3000, null, null, now));
            store.record(event("summary_request", "test-model", 1, 400, 100L, 20L, now));
            store.record(event("summary_request", "test-model", 0, 600, null, null, now));
            store.record(event("workflow", "", 1, 8000, null, null, now.minus(Duration.ofDays(10))));
            store.flush();
            var snapshot = store.snapshot(7);
            assertThat(snapshot.available()).isTrue();
            assertThat(snapshot.pendingEvents()).isZero();
            assertThat(snapshot.droppedEvents()).isZero();
            assertThat(snapshot.groups()).hasSize(2);
            var summary = snapshot.groups().get(0);
            assertThat(summary.path("stage").asText()).isEqualTo("summary_request");
            assertThat(summary.path("calls").asInt()).isEqualTo(2);
            assertThat(summary.path("input_tokens").asInt()).isEqualTo(100);
            assertThat(summary.path("output_tokens").asInt()).isEqualTo(20);
            assertThat(summary.path("usage_calls").asInt()).isEqualTo(1);
            var workflow = snapshot.groups().get(1);
            assertThat(workflow.path("avg_ms").asDouble()).isEqualTo(2000);
            assertThat(workflow.path("p95_ms").asDouble()).isBetween(1000.0, 3000.0);
            assertThat(workflow.path("succeeded").asInt()).isEqualTo(1);
            assertThat(workflow.path("failed").asInt()).isEqualTo(1);
            assertThat(snapshot.daily().get(0).path("attempts").asInt()).isEqualTo(2);
            assertThat(snapshot.recentFailures()).hasSize(2);
            assertThat(store.snapshot(30).groups().get(1).path("calls").asInt()).isEqualTo(3);
        } finally {
            store.stop();
            sql("DROP DATABASE " + database);
        }
    }

    private static void sql(String sql) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:53319/"))
                .timeout(Duration.ofSeconds(10)).header("X-ClickHouse-User", "voice_notes_test")
                .header("X-ClickHouse-Key", "local-test-only")
                .POST(HttpRequest.BodyPublishers.ofString(sql)).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
    }
}
