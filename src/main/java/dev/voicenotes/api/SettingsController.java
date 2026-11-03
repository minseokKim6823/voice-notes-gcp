package dev.voicenotes.api;

import dev.voicenotes.infrastructure.SummaryModelSettings;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SettingsController {
    private final SummaryModelSettings summary;
    public SettingsController(SummaryModelSettings summary) { this.summary = summary; }
    public record SummaryStatus(String provider, String model, boolean configured, String keyVariable, long timeoutSeconds) {}
    @GetMapping("/api/settings/summary")
    public ResponseEntity<SummaryStatus> summary() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new SummaryStatus(
                summary.displayName(), summary.model(), summary.configured(), summary.keyVariable(), summary.timeout().toSeconds()));
    }
}
