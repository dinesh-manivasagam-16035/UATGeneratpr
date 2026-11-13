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

import java.nio.charset.StandardCharsets;

/**
 * Issues HTTP calls against a Zoho CRM org using OAuth refresh-token flow.
 * Instances are per-request; access tokens are shared across instances through
 * SHARED_TOKENS so each refresh token is refreshed about once an hour.
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
    /** Set when the caller already holds an access token (Zia Agents connection). */
    private String staticAccessToken;

    /** A client that uses a ready-made access token and never refreshes. */
    public static CrmClient withAccessToken(String apiBase, String accessToken) {
        CrmClient c = new CrmClient(apiBase, "", "", "", "");
        c.staticAccessToken = accessToken;
        return c;
    }

    public CrmClient(String apiBase, String accountsBase, String clientId,
                     String clientSecret, String refreshToken) {
        this.apiBase = apiBase == null || apiBase.isEmpty() ? "https://www.zohoapis.com" : apiBase;
        this.accountsBase = accountsBase == null || accountsBase.isEmpty()
                ? "https://accounts.zoho.com" : accountsBase;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.refreshToken = refreshToken;
    }

    /** API host this client talks to, e.g. https://www.zohoapis.in. */
    public String apiBase() { return apiBase; }

    /**
     * The CRM org every call is routed to. A user in several orgs otherwise lands in their
     * default org whatever they picked; Zoho CRM reads the org from the X-CRM-ORG header.
     */
    private String orgId;
    private final java.util.Map<String, String> extraHeaders = new java.util.LinkedHashMap<>();
    /** Adds a header to every call (Zoho Desk wants orgId). */
    public CrmClient withHeader(String name, String value) { if (value == null || value.isEmpty()) extraHeaders.remove(name); else extraHeaders.put(name, value); return this; }

    public CrmClient forOrg(String orgId) { this.orgId = orgId == null || orgId.trim().isEmpty() ? null : orgId.trim(); return this; }
    public String orgId() { return orgId; }

    public boolean hasCredentials() {
        if (notEmpty(staticAccessToken)) return true;
        return notEmpty(clientId) && notEmpty(clientSecret) && notEmpty(refreshToken);
    }

    /**
     * A multipart/form-data call (Zoho's functions API takes its JSON as a "metadata" form part).
     * {@code parts} maps part name → text; every part is sent as application/json unless its name is "code",
     * which is sent as a .ds file (Deluge source).
     */
    public Response callMultipart(String method, String path, java.util.Map<String, String> parts) throws Exception {
        if (!hasCredentials()) throw new IllegalStateException("CRM credentials not provided.");
        String token = getAccessToken(60_000);
        String url = path.startsWith("http") ? path : apiBase + path;
        HttpUriRequestBase req = "PUT".equalsIgnoreCase(method) ? new HttpPut(url) : new HttpPost(url);
        req.setHeader("Authorization", "Zoho-oauthtoken " + token);
        if (orgId != null) req.setHeader("X-CRM-ORG", orgId);
        for (java.util.Map.Entry<String, String> h : extraHeaders.entrySet()) req.setHeader(h.getKey(), h.getValue());
        org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder mb =
                org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder.create();
        for (java.util.Map.Entry<String, String> p : parts.entrySet()) {
            if ("code".equals(p.getKey())) {
                mb.addBinaryBody("code", p.getValue().getBytes(StandardCharsets.UTF_8), ContentType.create("text/plain", StandardCharsets.UTF_8), "function.ds");
            } else {
                mb.addTextBody(p.getKey(), p.getValue(), ContentType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8));
            }
        }
        req.setEntity(mb.build());
        try (CloseableHttpClient http = HttpClients.createDefault()) {
            return http.execute(req, response -> {
                int code = response.getCode();
                String body = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity());
                JsonNode json = null;
                if (!body.isEmpty()) { try { json = MAPPER.readTree(body); } catch (Exception ignored) {} }
                return new Response(code, body, json);
            });
        }
    }

    public Response call(String method, String path, String jsonBody) throws Exception {
        if (!hasCredentials()) {
            throw new IllegalStateException("CRM credentials not provided.");
        }
        String token = getAccessToken(60_000);
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
        if (orgId != null) req.setHeader("X-CRM-ORG", orgId);
        for (java.util.Map.Entry<String, String> h : extraHeaders.entrySet()) req.setHeader(h.getKey(), h.getValue());
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

    /**
     * Access tokens shared by every CrmClient in this instance, keyed by a hash of
     * the credentials. Without this, each request built a new client and refreshed
     * the token, and Zoho limits refreshes to about 10 per 10 minutes per refresh token.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, String[]> SHARED_TOKENS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String cacheKey(String accountsBase, String clientId, String refreshToken) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(
                    (accountsBase + "|" + clientId + "|" + refreshToken).getBytes(StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(h);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Records a token just obtained elsewhere (connect / OAuth callback) so it isn't refreshed again. */
    public static void seed(String accountsBase, String clientId, String refreshToken,
                            String accessToken, long expiresAt) {
        if (!notEmpty(clientId) || !notEmpty(refreshToken) || !notEmpty(accessToken)) return;
        String ab = accountsBase == null || accountsBase.isEmpty() ? "https://accounts.zoho.com" : accountsBase;
        SHARED_TOKENS.put(cacheKey(ab, clientId, refreshToken),
                new String[]{accessToken, String.valueOf(expiresAt)});
    }

    /** A valid access token, refreshing if needed. */
    public String accessToken() throws Exception {
        return getAccessToken(60_000);
    }

    /** An access token valid for at least minValidMs more (refreshed if not), e.g. to hand to a background job. */
    public String accessToken(long minValidMs) throws Exception {
        return getAccessToken(Math.max(60_000, minValidMs));
    }

    private synchronized String getAccessToken(long minValidMs) throws Exception {
        if (notEmpty(staticAccessToken)) return staticAccessToken;
        long now = System.currentTimeMillis();
        if (cachedAccessToken != null && now < tokenExpiresAt - minValidMs) {
            return cachedAccessToken;
        }
        String key = cacheKey(accountsBase, clientId, refreshToken);
        String[] shared = SHARED_TOKENS.get(key);
        if (shared != null && now < Long.parseLong(shared[1]) - minValidMs) {
            cachedAccessToken = shared[0];
            tokenExpiresAt = Long.parseLong(shared[1]);
            return cachedAccessToken;
        }

        // Credentials go in the form body, not the URL, so they don't end up in access logs.
        try (CloseableHttpClient http = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(accountsBase + "/oauth/v2/token");
            post.setEntity(new org.apache.hc.client5.http.entity.UrlEncodedFormEntity(java.util.Arrays.asList(
                    new org.apache.hc.core5.http.message.BasicNameValuePair("refresh_token", refreshToken),
                    new org.apache.hc.core5.http.message.BasicNameValuePair("client_id", clientId),
                    new org.apache.hc.core5.http.message.BasicNameValuePair("client_secret", clientSecret),
                    new org.apache.hc.core5.http.message.BasicNameValuePair("grant_type", "refresh_token"))));
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
                SHARED_TOKENS.put(key, new String[]{tok, String.valueOf(tokenExpiresAt)});
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
