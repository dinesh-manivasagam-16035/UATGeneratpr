package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * CRM sign-in sessions.
 *
 * With SESSION_SECRET set (recommended), the session itself is the cookie value:
 * the refresh token and connection details are sealed with AES-256-GCM
 * (SessionCrypto). Nothing is stored server-side, so sessions survive restarts,
 * redeploys and multiple function instances. Access tokens are not stored in the
 * cookie; CrmClient caches them per instance and refreshes when needed.
 *
 * Without SESSION_SECRET, the old in-memory store is used and sessions are lost
 * whenever the instance restarts. /crm/status reports which mode is active.
 *
 * Callers use the same API as before: create(...) returns the cookie value,
 * get(cookieValue) returns the bundle or null.
 */
public class CrmTokenStore {

    private static final Logger LOG = Logger.getLogger(CrmTokenStore.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final String COOKIE = "tp_crm_sid";
    public static final int SESSION_DAYS = 30;
    private static final long SESSION_MS = SESSION_DAYS * 24L * 60 * 60 * 1000;

    private static final ConcurrentHashMap<String, TokenBundle> LEGACY = new ConcurrentHashMap<>();
    private static volatile boolean warned = false;

    /** True when sessions are sealed cookies that survive restarts. */
    public static boolean persistent() {
        return SessionCrypto.fromEnv() != null;
    }

    /** Create a session and return the cookie value. */
    public static String create(String accessToken, long expiresAt,
                                String refreshToken, String email,
                                String clientId, String clientSecret,
                                String accountsBase, String apiBase) {
        return create(accessToken, expiresAt, refreshToken, email, clientId, clientSecret,
                accountsBase, apiBase, null, null);
    }

    public static String create(String accessToken, long expiresAt,
                                String refreshToken, String email,
                                String clientId, String clientSecret,
                                String accountsBase, String apiBase,
                                String orgId, String orgName) {
        long sessionExpiresAt = System.currentTimeMillis() + SESSION_MS;
        TokenBundle b = new TokenBundle(accessToken, expiresAt, refreshToken, email,
                clientId, clientSecret, accountsBase, apiBase, sessionExpiresAt);
        b.orgId = orgId;
        b.orgName = orgName;
        CrmClient.seed(b.accountsBase, clientId, refreshToken, accessToken, expiresAt);

        SessionCrypto crypto = SessionCrypto.fromEnv();
        if (crypto == null) {
            if (!warned) {
                warned = true;
                LOG.warning("SESSION_SECRET is not set: CRM sessions are kept in memory and will be "
                        + "lost on restart. Set SESSION_SECRET to make them persistent.");
            }
            String sid = UUID.randomUUID().toString();
            LEGACY.put(sid, b);
            return sid;
        }

        ObjectNode p = MAPPER.createObjectNode();
        p.put("v", 1);
        p.put("rt", nz(refreshToken));
        p.put("em", nz(email));
        p.put("ab", b.accountsBase);
        p.put("api", b.apiBase);
        p.put("oid", nz(orgId));
        p.put("on", nz(orgName));
        p.put("exp", sessionExpiresAt);
        // Server-side OAuth client credentials (OAuth sign-in) are not copied into the
        // cookie; they're read from the environment when the session is opened.
        boolean envClient = clientId != null && clientId.equals(System.getenv("ZOHO_CLIENT_ID"))
                && clientSecret != null && clientSecret.equals(System.getenv("ZOHO_CLIENT_SECRET"));
        if (envClient) {
            p.put("envc", true);
        } else {
            p.put("cid", nz(clientId));
            p.put("cs", nz(clientSecret));
        }
        try {
            return crypto.seal(MAPPER.writeValueAsString(p));
        } catch (Exception e) {
            throw new IllegalStateException("Could not create session", e);
        }
    }

    /** The session for a cookie value, or null when missing, invalid, or expired. */
    public static TokenBundle get(String cookieValue) {
        if (cookieValue == null || cookieValue.isEmpty()) return null;
        if (!cookieValue.startsWith(SessionCrypto.PREFIX)) {
            TokenBundle b = LEGACY.get(cookieValue);
            return b == null || b.isExpired() ? null : b;
        }
        SessionCrypto crypto = SessionCrypto.fromEnv();
        if (crypto == null) return null;
        String json = crypto.open(cookieValue);
        if (json == null) return null;
        try {
            JsonNode p = MAPPER.readTree(json);
            long exp = p.path("exp").asLong(0);
            if (System.currentTimeMillis() > exp) return null;
            boolean envc = p.path("envc").asBoolean(false);
            String cid = envc ? System.getenv("ZOHO_CLIENT_ID") : p.path("cid").asText("");
            String cs = envc ? System.getenv("ZOHO_CLIENT_SECRET") : p.path("cs").asText("");
            TokenBundle b = new TokenBundle(null, 0, p.path("rt").asText(""), p.path("em").asText(""),
                    cid, cs, p.path("ab").asText(""), p.path("api").asText(""), exp);
            b.orgId = emptyToNull(p.path("oid").asText(""));
            b.orgName = emptyToNull(p.path("on").asText(""));
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    public static void remove(String cookieValue) {
        if (cookieValue != null) LEGACY.remove(cookieValue);
        // Sealed sessions have no server state; clearing the cookie ends them.
    }

    /** Kept for compatibility; access tokens now live in CrmClient's shared cache. */
    public static void refresh(String cookieValue, String newAccessToken, long newExpiresAt) {
        TokenBundle b = get(cookieValue);
        if (b != null) {
            b.accessToken = newAccessToken;
            b.expiresAt = newExpiresAt;
            CrmClient.seed(b.accountsBase, b.clientId, b.refreshToken, newAccessToken, newExpiresAt);
        }
    }

    /**
     * Sets the session cookie: HttpOnly, SameSite=Lax, and Secure everywhere except
     * plain-http localhost (catalyst serve). maxAgeSeconds 0 clears it.
     */
    public static void setCookie(HttpServletRequest req, HttpServletResponse resp,
                                 String value, int maxAgeSeconds) {
        String host = req.getHeader("Host");
        host = host == null ? "" : host.toLowerCase();
        boolean local = host.startsWith("localhost") || host.startsWith("127.0.0.1");
        resp.addHeader("Set-Cookie", COOKIE + "=" + (value == null ? "" : value)
                + "; Path=/; Max-Age=" + Math.max(0, maxAgeSeconds)
                + "; HttpOnly; SameSite=Lax" + (local ? "" : "; Secure"));
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static String emptyToNull(String s) { return s == null || s.isEmpty() ? null : s; }

    public static final class TokenBundle {
        public String accessToken;          // may be null; use toCrmClient().accessToken()
        public long   expiresAt;            // access-token expiry (0 when unknown)
        public final long sessionExpiresAt;
        public final String refreshToken;
        public final String email;
        public final String clientId;
        public final String clientSecret;
        public final String accountsBase;   // e.g. https://accounts.zoho.in
        public final String apiBase;        // e.g. https://www.zohoapis.in
        public volatile String orgId;
        public volatile String orgName;

        TokenBundle(String accessToken, long expiresAt,
                    String refreshToken, String email,
                    String clientId, String clientSecret,
                    String accountsBase, String apiBase, long sessionExpiresAt) {
            this.accessToken  = accessToken;
            this.expiresAt    = expiresAt;
            this.refreshToken = refreshToken;
            this.email        = email;
            this.clientId     = clientId;
            this.clientSecret = clientSecret;
            this.accountsBase = accountsBase != null && !accountsBase.isEmpty()
                    ? accountsBase : "https://accounts.zoho.com";
            this.apiBase      = apiBase != null && !apiBase.isEmpty()
                    ? apiBase : "https://www.zohoapis.com";
            this.sessionExpiresAt = sessionExpiresAt;
        }

        /**
         * True when the sign-in session has ended. This used to check the one-hour
         * access token, which logged users out every hour even though the refresh
         * token was still valid.
         */
        public boolean isExpired() {
            return System.currentTimeMillis() > sessionExpiresAt;
        }

        /** True when this bundle can refresh its own access token. */
        public boolean hasOAuthCredentials() {
            return clientId != null && !clientId.isEmpty()
                    && clientSecret != null && !clientSecret.isEmpty()
                    && refreshToken != null && !refreshToken.isEmpty();
        }

        public CrmClient toCrmClient() {
            return new CrmClient(apiBase, accountsBase, clientId, clientSecret, refreshToken);
        }
    }
}
