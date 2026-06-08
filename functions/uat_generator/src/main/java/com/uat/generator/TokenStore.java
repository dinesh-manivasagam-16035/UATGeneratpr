package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists OAuth token bundles per browser session id (sid).
 *
 * Backed by Catalyst ZCache when available (production AppSail), with
 * a process-local ConcurrentHashMap fallback for local Jetty / unit
 * testing. Refresh-on-demand keeps access tokens fresh.
 */
public class TokenStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String KEY_PREFIX = "uat:tok:";
    /** ZCache TTL in seconds (90 days; refresh-token lifetime is what matters). */
    private static final int TTL_SECONDS = 60 * 60 * 24 * 90;
    /** Refresh access token if it expires within this window. */
    private static final long REFRESH_SKEW_MS = 60_000L;

    private static final ConcurrentHashMap<String, String> FALLBACK = new ConcurrentHashMap<>();

    public static class TokenBundle {
        public String accessToken;
        public String refreshToken;
        /** Epoch ms when accessToken expires. */
        public long expiresAt;
        public String apiDomain;
        public String accountsBase;
        public String email;
        public String userId;

        public TokenBundle() {}
    }

    private TokenStore() {}

    public static void put(String sid, TokenBundle bundle) {
        if (sid == null || sid.isEmpty() || bundle == null) return;
        try {
            String json = MAPPER.writeValueAsString(bundle);
            if (!cachePut(KEY_PREFIX + sid, json)) {
                FALLBACK.put(sid, json);
            }
        } catch (Exception e) {
            // last-ditch: keep it in memory so this request still works
            try { FALLBACK.put(sid, MAPPER.writeValueAsString(bundle)); } catch (Exception ignored) {}
        }
    }

    public static TokenBundle get(String sid) {
        if (sid == null || sid.isEmpty()) return null;
        String json = cacheGet(KEY_PREFIX + sid);
        if (json == null) json = FALLBACK.get(sid);
        if (json == null) return null;
        try {
            return MAPPER.readValue(json, TokenBundle.class);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean has(String sid) {
        return get(sid) != null;
    }

    public static void delete(String sid) {
        if (sid == null || sid.isEmpty()) return;
        cacheDelete(KEY_PREFIX + sid);
        FALLBACK.remove(sid);
    }

    /**
     * Returns a valid access token, refreshing if it is near expiry.
     * Returns null when the sid has no stored bundle.
     */
    public static String getAccessToken(String sid) throws Exception {
        TokenBundle b = get(sid);
        if (b == null) return null;
        if (b.expiresAt - System.currentTimeMillis() < REFRESH_SKEW_MS) {
            refresh(sid, b);
        }
        return b.accessToken;
    }

    /** Forces a refresh using the stored refresh_token; updates the store. */
    public static synchronized TokenBundle refresh(String sid, TokenBundle b) throws Exception {
        if (b == null || b.refreshToken == null || b.refreshToken.isEmpty()) {
            throw new IllegalStateException("No refresh_token on session");
        }
        String clientId = System.getenv("ZOHO_OAUTH_CLIENT_ID");
        String clientSecret = System.getenv("ZOHO_OAUTH_CLIENT_SECRET");
        if (clientId == null || clientSecret == null) {
            throw new IllegalStateException(
                "ZOHO_OAUTH_CLIENT_ID / ZOHO_OAUTH_CLIENT_SECRET env vars not set");
        }
        String accountsBase = (b.accountsBase == null || b.accountsBase.isEmpty())
                ? "https://accounts.zoho.com" : b.accountsBase;

        String form = "refresh_token=" + URLEncoder.encode(b.refreshToken, StandardCharsets.UTF_8)
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)
                + "&grant_type=refresh_token";

        HttpPost post = new HttpPost(accountsBase + "/oauth/v2/token");
        post.setEntity(new StringEntity(form, ContentType.APPLICATION_FORM_URLENCODED));

        try (CloseableHttpClient http = HttpClients.createDefault()) {
            String body = http.execute(post, response -> {
                int sc = response.getCode();
                String raw = response.getEntity() == null ? "" : EntityUtils.toString(response.getEntity());
                if (sc >= 200 && sc < 300) return raw;
                throw new RuntimeException("Refresh failed (" + sc + "): " + raw);
            });
            JsonNode json = MAPPER.readTree(body);
            if (!json.hasNonNull("access_token")) {
                throw new RuntimeException("Refresh response missing access_token: " + body);
            }
            b.accessToken = json.get("access_token").asText();
            long expiresIn = json.has("expires_in") ? json.get("expires_in").asLong(3600) : 3600L;
            b.expiresAt = System.currentTimeMillis() + (expiresIn * 1000L);
            // refresh-token rotation is uncommon for Zoho but honor if present
            if (json.hasNonNull("refresh_token")) {
                b.refreshToken = json.get("refresh_token").asText();
            }
            if (json.hasNonNull("api_domain")) {
                b.apiDomain = json.get("api_domain").asText();
            }
            put(sid, b);
            return b;
        }
    }

    // ---------- ZCache reflective wrapper (graceful fallback) ----------

    private static volatile Boolean zcacheAvailable = null;
    private static volatile Object segment;

    private static boolean ensureSegment() {
        if (Boolean.FALSE.equals(zcacheAvailable)) return false;
        if (segment != null) return true;
        synchronized (TokenStore.class) {
            if (segment != null) return true;
            try {
                Class<?> cls = Class.forName("com.zc.component.cache.ZCache");
                Object inst = cls.getMethod("getInstance").invoke(null);
                String segName = System.getenv().getOrDefault("ZCACHE_SEGMENT", "default");
                Object seg;
                try {
                    seg = inst.getClass().getMethod("getSegmentDetails", String.class).invoke(inst, segName);
                } catch (NoSuchMethodException e) {
                    seg = inst.getClass().getMethod("getSegment", String.class).invoke(inst, segName);
                }
                segment = seg;
                zcacheAvailable = Boolean.TRUE;
                return true;
            } catch (Throwable t) {
                zcacheAvailable = Boolean.FALSE;
                return false;
            }
        }
    }

    private static boolean cachePut(String key, String value) {
        if (!ensureSegment()) return false;
        try {
            // Try (String, String, int) signature first; fall back to (String, String).
            try {
                Method m = segment.getClass().getMethod("put", String.class, String.class, int.class);
                m.invoke(segment, key, value, TTL_SECONDS);
            } catch (NoSuchMethodException e) {
                Method m = segment.getClass().getMethod("put", String.class, String.class);
                m.invoke(segment, key, value);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String cacheGet(String key) {
        if (!ensureSegment()) return null;
        try {
            Object out = segment.getClass().getMethod("get", String.class).invoke(segment, key);
            if (out == null) return null;
            if (out instanceof String) return (String) out;
            if (out instanceof Map) {
                Object v = ((Map<?, ?>) out).get("cache_value");
                return v == null ? null : v.toString();
            }
            return out.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void cacheDelete(String key) {
        if (!ensureSegment()) return;
        try {
            segment.getClass().getMethod("delete", String.class).invoke(segment, key);
        } catch (Throwable ignored) {
            // best-effort
        }
    }
}
