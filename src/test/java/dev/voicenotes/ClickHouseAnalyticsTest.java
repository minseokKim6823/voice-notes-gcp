package dev.voicenotes.infrastructure;

import dev.voicenotes.api.AnalyticsController;
import dev.voicenotes.application.ProcessingAnalytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ClickHouseAnalyticsTest {
    private HttpServer server;
    private ClickHouseAnalytics store;
    private final List<String> bodies = new ArrayList<>();
    private final List<String> queries = new ArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            queries.add(exchange.getRequestURI().getQuery());
            assertThat(exchange.getRequestHeaders().getFirst("X-ClickHouse-Key")).isEqualTo("test-secret");
            byte[] response = (body.startsWith("SELECT") ? "{\"data\":[]}" : "").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), response.length == 0 ? -1 : response.length);
            if (response.length != 0) exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        store = create(true);
    }

    private ClickHouseAnalytics create(boolean enabled) {
        return new ClickHouseAnalytics(new ObjectMapper(), enabled, "http://127.0.0.1:" + server.getAddress().getPort(),
                "test_db", "test-user", "test-secret", Duration.ofSeconds(1));
    }

    @AfterEach void stop() { store.stop(); server.stop(0); }

    static ProcessingAnalytics.Event event(String stage, String model, int success, long duration, Long input, Long output, Instant date) {
        return new ProcessingAnalytics.Event(UUID.randomUUID().toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                date.toString(), "process", stage, stage.equals("workflow") ? "" : "exaone", model, success, duration, input, output,
                success == 1 ? "" : "IOException");
    }

    @Test void queuesWithoutNetworkThenWritesOneEscapedBatchAndParameterizedQueries() throws Exception {
        store.record(event("summary_request", "model\"\n한글", 1, 50, 23L, 5L, Instant.now()));
        store.record(event("workflow", "", 1, 100, null, null, Instant.now()));
        assertThat(bodies).isEmpty();
        store.flush();
        assertThat(bodies).hasSize(2);
        String[] lines = bodies.get(1).split("\n");
        assertThat(lines).hasSize(3);
        var row = new ObjectMapper().readTree(lines[1]);
        assertThat(row.path("model").asText()).isEqualTo("model\"\n한글");
        assertThat(row.path("input_tokens").asInt()).isEqualTo(23);
        assertThat(new ObjectMapper().readTree(lines[2]).path("input_tokens").isNull()).isTrue();
        assertThat(store.snapshot(7).available()).isTrue();
        assertThat(queries).filteredOn(query -> query.contains("param_days=7")).hasSize(3);
        assertThat(bodies).filteredOn(body -> body.startsWith("SELECT")).allMatch(body -> body.contains("{days:UInt16}"));
    }

    @Test void failedDeliveryDoesNotThrowOrReplayAndRecoversForNewEvents() {
        status.set(503);
        store.record(event("workflow", "", 1, 10, null, null, Instant.now()));
        assertThatCode(store::flush).doesNotThrowAnyException();
        var unavailable = store.snapshot(7);
        assertThat(unavailable.available()).isFalse();
        assertThat(unavailable.droppedEvents()).isEqualTo(1);
        assertThat(unavailable.pendingEvents()).isZero();
        status.set(200);
        store.record(event("workflow", "", 1, 20, null, null, Instant.now()));
        store.flush();
        assertThat(bodies).filteredOn(body -> body.startsWith("INSERT")).hasSize(1);
        assertThat(store.snapshot(7).available()).isTrue();
    }

    @Test void boundsQueueAndExposesDisabledUnavailableAndValidationStates() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new AnalyticsController(store)).build();
        mvc.perform(get("/api/analytics?days=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/analytics?days=91")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/analytics?days=7%20OR%201=1")).andExpect(status().isBadRequest());
        assertThat(bodies).isEmpty();
        status.set(503);
        mvc.perform(get("/api/analytics")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.available").value(false));
        var event = event("workflow", "", 1, 1, null, null, Instant.now());
        for (int i = 0; i < 10_001; i++) store.record(event);
        var snapshot = store.snapshot(1);
        assertThat(snapshot.pendingEvents()).isEqualTo(10_000);
        assertThat(snapshot.droppedEvents()).isEqualTo(1);
        try {
            var disabled = create(false);
            try {
                int requests = bodies.size();
                disabled.record(event);
                disabled.flush();
                MockMvcBuilders.standaloneSetup(new AnalyticsController(disabled)).build()
                        .perform(get("/api/analytics")).andExpect(status().isOk())
                        .andExpect(jsonPath("$.enabled").value(false));
                assertThat(bodies).hasSize(requests);
            } finally { disabled.stop(); }
        } finally { status.set(503); }
    }
}
