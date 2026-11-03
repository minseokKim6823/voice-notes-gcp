package dev.voicenotes.infrastructure;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/** Credentials stay on the server; only explicit public status is sent to the browser. */
@Component
public class SummaryModelSettings {
    private final String provider;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final int maxOutputTokens;

    public SummaryModelSettings(Environment env) {
        provider = env.getProperty("voice-notes.summary.provider", "groq").strip().toLowerCase(Locale.ROOT);
        if (!Set.of("groq", "glm", "compatible").contains(provider)) {
            throw new IllegalArgumentException("LLM_PROVIDER는 groq, glm 또는 compatible이어야 합니다.");
        }
        String defaultBase = provider.equals("groq") ? "https://api.groq.com/openai/v1"
                : provider.equals("glm") ? "https://api.z.ai/api/paas/v4" : "";
        String defaultModel = provider.equals("groq") ? "openai/gpt-oss-120b"
                : provider.equals("glm") ? "glm-4.7-flash" : "";
        baseUrl = first(env.getProperty("voice-notes.summary.base-url"),
                provider.equals("glm") ? env.getProperty("GLM_BASE_URL") : null,
                !provider.equals("groq") ? env.getProperty("EXAONE_BASE_URL") : null, defaultBase).replaceAll("/+$", "");
        model = first(env.getProperty("voice-notes.summary.model"),
                provider.equals("glm") ? env.getProperty("GLM_MODEL") : null,
                !provider.equals("groq") ? env.getProperty("EXAONE_MODEL") : null, defaultModel);
        apiKey = first(env.getProperty("voice-notes.summary.api-key"), env.getProperty(keyVariable()),
                !provider.equals("groq") ? env.getProperty("EXAONE_API_KEY") : null);
        timeout = Duration.ofSeconds(env.getProperty("voice-notes.summary.timeout-seconds", Long.class, 90L));
        maxOutputTokens = env.getProperty("voice-notes.summary.max-output-tokens", Integer.class, 2048);
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(5)) > 0
                || maxOutputTokens < 256 || maxOutputTokens > 8192) {
            throw new IllegalArgumentException("요약 시간 제한은 1~300초, 출력 토큰은 256~8192로 설정하세요.");
        }
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.strip();
        return "";
    }
    public String provider() { return provider; }
    public String model() { return model; }
    public String apiKey() { return apiKey; }
    public Duration timeout() { return timeout; }
    public int maxOutputTokens() { return maxOutputTokens; }
    public String keyVariable() { return provider.equals("groq") ? "GROQ_API_KEY" : provider.equals("glm") ? "GLM_API_KEY" : "LLM_API_KEY"; }
    public String displayName() { return provider.equals("groq") ? "Groq" : provider.equals("glm") ? "Z.ai" : "요약 서버"; }
    public boolean configured() { return !baseUrl.isBlank() && !model.isBlank() && (!apiKey.isBlank() || provider.equals("compatible")); }
    public boolean groqReasoning() { return provider.equals("groq") && model.startsWith("openai/gpt-oss-"); }

    public URI endpoint() {
        try {
            URI endpoint = URI.create(baseUrl + "/chat/completions");
            if (!("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme()))
                    || endpoint.getHost() == null || endpoint.getUserInfo() != null
                    || endpoint.getQuery() != null || endpoint.getFragment() != null) throw new IllegalArgumentException();
            return endpoint;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("LLM_BASE_URL에 올바른 HTTP(S) 요약 API 주소를 설정하세요.");
        }
    }
    public void validate() {
        endpoint();
        if (model.isBlank()) throw new IllegalStateException("LLM_MODEL에 요약 모델을 설정하세요.");
        if (apiKey.isBlank() && !provider.equals("compatible")) {
            throw new IllegalStateException(keyVariable() + "에 " + displayName() + " API 키를 설정하고 서버를 재시작하세요. 전사는 보존되어 있습니다.");
        }
    }
}
