package com.uat.generator;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CrmTokenStore {
    private static final ConcurrentHashMap<String, TokenBundle> STORE = new ConcurrentHashMap<>();

    /** Create session with OAuth credentials + current access token. */
    public static String create(String accessToken, long expiresAt,
                                String refreshToken, String email,
                                String clientId, String clientSecret,
                                String accountsBase, String apiBase) {
        String sid = UUID.randomUUID().toString();
        STORE.put(sid, new TokenBundle(accessToken, expiresAt, refreshToken, email,
                clientId, clientSecret, accountsBase, apiBase));
        return sid;
    }

    /** Backward-compat overload used by CrmCallbackServlet (OAuth flow). */
    public static String create(String accessToken, long expiresAt,
                                String refreshToken, String email) {
        return create(accessToken, expiresAt, refreshToken, email,
                null, null, null, null);
    }

    public static TokenBundle get(String sid) {
        return sid == null ? null : STORE.get(sid);
    }

    public static void remove(String sid) {
        if (sid != null) STORE.remove(sid);
    }

    /** Refresh the stored access token in-place without changing the session id. */
    public static void refresh(String sid, String newAccessToken, long newExpiresAt) {
        TokenBundle b = STORE.get(sid);
        if (b != null) {
            b.accessToken = newAccessToken;
            b.expiresAt   = newExpiresAt;
        }
    }

    public static final class TokenBundle {
        public String accessToken;
        public long   expiresAt;
        public final String refreshToken;
        public final String email;
        // OAuth client credentials — present when user connected via credential form.
        public final String clientId;
        public final String clientSecret;
        public final String accountsBase; // e.g. https://accounts.zoho.in
        public final String apiBase;      // e.g. https://www.zohoapis.in

        TokenBundle(String accessToken, long expiresAt,
                    String refreshToken, String email,
                    String clientId, String clientSecret,
                    String accountsBase, String apiBase) {
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
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt - 60_000L;
        }

        /** True when this bundle includes OAuth client credentials (not just a server-issued token). */
        public boolean hasOAuthCredentials() {
            return clientId != null && !clientId.isEmpty()
                    && clientSecret != null && !clientSecret.isEmpty()
                    && refreshToken != null && !refreshToken.isEmpty();
        }

        /** Build a CrmClient from this bundle's credentials. */
        public CrmClient toCrmClient() {
            return new CrmClient(apiBase, accountsBase, clientId, clientSecret, refreshToken);
        }
    }
}
