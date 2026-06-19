package com.uat.generator;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Adds CORS headers only when the request looks like local development.
 *
 * In Catalyst AppSail, the gateway already emits Access-Control-Allow-Origin
 * (with the requesting origin) and Access-Control-Allow-Credentials. If we
 * also emit our own Access-Control-Allow-Origin: *, the browser sees two
 * conflicting headers (one specific origin with credentials=true, one
 * wildcard) and rejects the response as a CORS violation.
 *
 * For local dev, the embedded Jetty has no gateway, so we still need to emit
 * the headers ourselves. We detect "local" by the Host/serverName.
 */
public final class CorsSupport {

    private CorsSupport() {}

    public static void apply(HttpServletRequest req, HttpServletResponse resp) {
        if (isLocal(req)) {
            resp.setHeader("Access-Control-Allow-Origin", "*");
        }
    }

    public static void applyOptions(HttpServletRequest req, HttpServletResponse resp) {
        if (isLocal(req)) {
            resp.setHeader("Access-Control-Allow-Origin", "*");
            resp.setHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
        }
        resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private static boolean isLocal(HttpServletRequest req) {
        String host = req.getServerName();
        return host == null
                || "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || host.endsWith(".local");
    }
}
