package com.uat.generator;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.UUID;

/**
 * Session id helpers. Uses a HttpOnly first-party cookie to identify a browser
 * session so we can key OAuth tokens in {@link TokenStore} per user.
 */
public final class SessionUtil {
    public static final String COOKIE = "sid";
    private static final int MAX_AGE_SECONDS = 60 * 60 * 24 * 30; // 30 days

    private SessionUtil() {}

    /** Returns the existing sid cookie value, or null if not present. */
    public static String getSid(HttpServletRequest req) {
        if (req == null) return null;
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (COOKIE.equals(c.getName())) {
                String v = c.getValue();
                if (v != null && !v.isEmpty()) return v;
            }
        }
        return null;
    }

    /** Returns the existing sid, or generates one and emits a Set-Cookie header. */
    public static String getOrCreateSid(HttpServletRequest req, HttpServletResponse resp) {
        String existing = getSid(req);
        if (existing != null) return existing;
        String sid = UUID.randomUUID().toString().replace("-", "");
        writeCookie(resp, sid, MAX_AGE_SECONDS, req != null && req.isSecure());
        return sid;
    }

    /** Clears the sid cookie on the client. */
    public static void clear(HttpServletResponse resp) {
        writeCookie(resp, "", 0, false);
    }

    private static void writeCookie(HttpServletResponse resp, String value, int maxAge, boolean secure) {
        StringBuilder sb = new StringBuilder();
        sb.append(COOKIE).append('=').append(value);
        sb.append("; Max-Age=").append(maxAge);
        sb.append("; Path=/");
        sb.append("; HttpOnly");
        sb.append("; SameSite=Lax");
        if (secure) sb.append("; Secure");
        resp.addHeader("Set-Cookie", sb.toString());
    }
}
