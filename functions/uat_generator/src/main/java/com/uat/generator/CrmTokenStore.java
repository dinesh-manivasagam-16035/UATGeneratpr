package com.uat.generator;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CrmTokenStore {
    private static final ConcurrentHashMap<String, TokenBundle> STORE = new ConcurrentHashMap<>();

    public static String create(String accessToken, long expiresAt,
                                String refreshToken, String email) {
        String sid = UUID.randomUUID().toString();
        STORE.put(sid, new TokenBundle(accessToken, expiresAt, refreshToken, email));
        return sid;
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

        TokenBundle(String accessToken, long expiresAt,
                    String refreshToken, String email) {
            this.accessToken  = accessToken;
            this.expiresAt    = expiresAt;
            this.refreshToken = refreshToken;
            this.email        = email;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt - 60_000L;
        }
    }
}
