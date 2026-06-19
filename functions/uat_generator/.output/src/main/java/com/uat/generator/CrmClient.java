package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPatch;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Issues HTTP calls against a Zoho CRM org using OAuth refresh-token flow.
 * Instances are per-request (credentials come from the client UI), so we
 * cache the access token in this instance, not in a static field.
 */
public class CrmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiBase;
    private final String accountsBase;
    private final String clientId;
    private final String clientSecret;
    private final String refreshToken;

    private String cachedAccessToken;
    private long tokenExpiresAt;

    public CrmClient(String apiBase, String accountsBase, String clientId,
                     String clientSecret, String refreshToken) {
        this.apiBase = apiBase == null || apiBase.isEmpty() ? "https://www.zohoapis.com" : apiBase;
        this.accountsBase = accountsBase == null || accountsBase.isEmpty()
                ? "https://accounts.zoho.com" : accountsBase;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.refreshToken = refreshToken;
    }

    public boolean hasCredentials() {
        return notEmpty(clientId) && notEmpty(clientSecret) && notEmpty(refreshToken);
    }

    public Response call(String method, String path, String jsonBody) throws Exception {
        if (!hasCredentials()) {
            throw new IllegalStateException("CRM credentials not provided.");
        }
        String token = getAccessToken();
        String url = path.startsWith("http") ? path : apiBase + path;

        HttpUriRequestBase req;
        String m = method == null ? "GET" : method.toUpperCase();
        switch (m) {
            case "POST":   req = new HttpPost(url); break;
            case "PUT":    req = new HttpPut(url); break;
            case "PATCH":  req = new HttpPatch(url); break;
            case "DELETE": req = new HttpDelete(url); break;
            case "GET":
            default:       req = new HttpGet(url); break;
        }
        req.setHeader("Authorization", "Zoho-oauthtoken " + token);
        if (jsonBody != null && !jsonBody.isEmpty()
                && (req instanceof HttpPost || req instanceof HttpPut || req instanceof HttpPatch)) {
            req.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));
        }

        try (CloseableHttpClient http = HttpClients.createDefault()) {
            return http.execute(req, response -> {
                int code = response.getCode();
                String body = response.getEntity() == null ? ""
                        : EntityUtils.toString(response.getEntity());
                JsonNode json = null;
                if (!body.isEmpty()) {
                    try { json = MAPPER.readTree(body); } catch (Exception ignored) {}
                }
                return new Response(code, body, json);
            });
        }
    }

    private synchronized String getAccessToken() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedAccessToken != null && now < tokenExpiresAt - 60_000) {
            return cachedAccessToken;
        }
        String url = accountsBase + "/oauth/v2/token"
                + "?refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
                + "&grant_type=refresh_token";

        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(url);
            return http.execute(post, response -> {
                String body = EntityUtils.toString(response.getEntity());
                if (response.getCode() >= 400) {
                    throw new RuntimeException("Zoho CRM OAuth refresh failed: " + body);
                }
                JsonNode root = MAPPER.readTree(body);
                String tok = root.path("access_token").asText("");
                if (tok.isEmpty()) {
                    throw new RuntimeException("OAuth response missing access_token: " + body);
                }
                cachedAccessToken = tok;
                long expiresIn = root.path("expires_in").asLong(3600);
                tokenExpiresAt = System.currentTimeMillis() + expiresIn * 1000;
                return cachedAccessToken;
            });
        }
    }

    private static boolean notEmpty(String s) { return s != null && !s.trim().isEmpty(); }

    public static class Response {
        public final int statusCode;
        public final String rawBody;
        public final JsonNode json;

        public Response(int statusCode, String rawBody, JsonNode json) {
            this.statusCode = statusCode;
            this.rawBody = rawBody;
            this.json = json;
        }
    }
}
