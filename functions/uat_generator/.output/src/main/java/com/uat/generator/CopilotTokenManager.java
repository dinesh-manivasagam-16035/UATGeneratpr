package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;

/**
 * Exchanges a GitHub OAuth/PAT token for a short-lived Copilot API token
 * and caches it until 60 seconds before expiry.
 */
public final class CopilotTokenManager {

    private static final ObjectMapper MAPPER     = new ObjectMapper();
    private static final String       TOKEN_URL  = "https://api.github.com/copilot_internal/v2/token";
    private static final long         BUFFER_SEC = 60;

    private static String cachedToken;
    private static long   expiresAt; // Unix epoch seconds

    private CopilotTokenManager() {}

    static synchronized String getToken(String githubToken) throws Exception {
        long now = System.currentTimeMillis() / 1000;
        if (cachedToken != null && expiresAt - now > BUFFER_SEC) {
            return cachedToken;
        }
        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpGet get = new HttpGet(TOKEN_URL);
            get.setHeader("Authorization", "Bearer " + githubToken);
            get.setHeader("editor-version", "vscode/1.85.0");
            get.setHeader("editor-plugin-version", "copilot-chat/0.22.4");
            get.setHeader("user-agent", "GitHubCopilotChat/0.22.4");

            return http.execute(get, response -> {
                String body = EntityUtils.toString(response.getEntity());
                int    code = response.getCode();
                if (code >= 400) {
                    throw new RuntimeException(
                        "Copilot token exchange failed (HTTP " + code + "): " + body);
                }
                JsonNode json  = MAPPER.readTree(body);
                String   token = json.path("token").asText();
                long     exp   = json.path("expires_at").asLong(0);
                if (token.isEmpty()) {
                    throw new RuntimeException(
                        "Copilot token response missing 'token' field: " + body);
                }
                cachedToken = token;
                expiresAt   = exp > 0 ? exp : (System.currentTimeMillis() / 1000 + 1800);
                return token;
            });
        }
    }
}
