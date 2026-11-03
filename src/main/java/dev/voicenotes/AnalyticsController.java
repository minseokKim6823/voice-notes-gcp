package dev.voicenotes;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {
    private final ClickHouseAnalytics analytics;

    public AnalyticsController(ClickHouseAnalytics analytics) { this.analytics = analytics; }

    @GetMapping
    public ResponseEntity<ClickHouseAnalytics.Snapshot> get(@RequestParam(defaultValue = "7") int days) {
        var snapshot = analytics.snapshot(days);
        return ResponseEntity.status(snapshot.enabled() && !snapshot.available() ? 503 : 200)
                .cacheControl(CacheControl.noStore()).body(snapshot);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }
}
