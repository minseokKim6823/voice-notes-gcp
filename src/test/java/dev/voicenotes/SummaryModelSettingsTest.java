package dev.voicenotes;

import dev.voicenotes.api.SettingsController;
import dev.voicenotes.infrastructure.SummaryModelSettings;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SummaryModelSettingsTest {
    @Test
    void usesProductionGroqModelAndDoesNotSendLegacyCredentialsToTheNewProvider() {
        var settings = new SummaryModelSettings(new MockEnvironment()
                .withProperty("GLM_API_KEY", "legacy-secret")
                .withProperty("EXAONE_API_KEY", "old-secret"));
        assertThat(settings.provider()).isEqualTo("groq");
        assertThat(settings.model()).isEqualTo("openai/gpt-oss-120b");
        assertThat(settings.endpoint().toString()).isEqualTo("https://api.groq.com/openai/v1/chat/completions");
        assertThat(settings.apiKey()).isEmpty();
        assertThat(settings.timeout().toSeconds()).isEqualTo(90);
        assertThatThrownBy(settings::validate).hasMessageContaining("GROQ_API_KEY");
    }

    @Test
    void supportsExplicitGlmAndKeylessCompatibleServers() {
        var glm = new SummaryModelSettings(new MockEnvironment().withProperty("voice-notes.summary.provider", "glm")
                .withProperty("GLM_API_KEY", "glm-secret"));
        assertThat(glm.model()).isEqualTo("glm-4.7-flash");
        assertThat(glm.endpoint().getHost()).isEqualTo("api.z.ai");
        assertThat(glm.configured()).isTrue();
        var local = new SummaryModelSettings(new MockEnvironment().withProperty("voice-notes.summary.provider", "compatible")
                .withProperty("voice-notes.summary.base-url", "http://localhost:8000/v1")
                .withProperty("voice-notes.summary.model", "local-model"));
        assertThat(local.configured()).isTrue();
        assertThatCode(local::validate).doesNotThrowAnyException();
    }

    @Test
    void publicSettingsExposePresenceButNeverTheKey() throws Exception {
        var settings = new SummaryModelSettings(new MockEnvironment().withProperty("GROQ_API_KEY", "private-groq-secret"));
        var result = MockMvcBuilders.standaloneSetup(new SettingsController(settings)).build()
                .perform(get("/api/settings/summary")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.configured").value(true))
                .andExpect(jsonPath("$.model").value("openai/gpt-oss-120b")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("private-groq-secret");
    }
}
