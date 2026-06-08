package com.uat.generator;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * GET /auth/login
 *
 * Redirects the browser to the Zoho OAuth authorization endpoint.
 *
 * Query params accepted:
 *   prompt  – forwarded verbatim; use "none" for silent SSO iframe,
 *             defaults to "consent" for interactive login.
 *   next    – URL-encoded destination after auth; stored in state so
 *             AuthCallbackServlet can redirect there after token exchange.
 *
 * Environment variables required:
 *   ZOHO_OAUTH_CLIENT_ID   – OAuth client id
 *   OAUTH_REDIRECT_BASE    – base URL of this deployment
 *                            e.g. https://uatgenerator-xxx.catalystappsail.in
 *   ZOHO_ACCOUNTS_BASE     – optional, defaults to https://accounts.zoho.com
 */
@WebServlet("/auth/login")
public class AuthLoginServlet extends HttpServlet {

    private static final String SCOPES =
            "ZohoCRM.modules.ALL,"
            + "ZohoCRM.settings.ALL,"
            + "ZohoCRM.functions.execute.READ,"
            + "ZohoProjects.projects.ALL,"
            + "ZohoProjects.bugs.ALL,"
            + "AaaServer.profile.READ";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {

        CorsSupport.addCorsHeaders(req, resp);

        String clientId = System.getenv("ZOHO_OAUTH_CLIENT_ID");
        if (clientId == null || clientId.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "ZOHO_OAUTH_CLIENT_ID not configured");
            return;
        }

        String redirectBase = System.getenv("OAUTH_REDIRECT_BASE");
        if (redirectBase == null || redirectBase.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "OAUTH_REDIRECT_BASE not configured");
            return;
        }
        // Strip trailing slash
        if (redirectBase.endsWith("/")) {
            redirectBase = redirectBase.substring(0, redirectBase.length() - 1);
        }

        String accountsBase = System.getenv().getOrDefault(
                "ZOHO_ACCOUNTS_BASE", "https://accounts.zoho.com");
        if (accountsBase.endsWith("/")) {
            accountsBase = accountsBase.substring(0, accountsBase.length() - 1);
        }

        // Ensure the session cookie is set; use it as state so the callback
        // can match the token to the right browser session.
        String sid = SessionUtil.getOrCreateSid(req, resp);

        // Optional: caller may pass a 'next' redirect target (URL-encoded).
        // We encode it inside the state as  sid|next  so callback can extract.
        // For silent / iframe flows (prompt=none) we append |iframe so the
        // callback can respond with a postMessage instead of a redirect.
        String next = req.getParameter("next");
        String promptParam = req.getParameter("prompt");
        String prompt = "none".equals(promptParam) ? "none" : "consent";

        String state;
        if ("none".equals(prompt)) {
            // state = sid|<next-or-empty>|iframe
            state = sid + "|" + (next != null ? next : "") + "|iframe";
        } else if (next != null && !next.isEmpty()) {
            state = sid + "|" + next;
        } else {
            state = sid;
        }

        String redirectUri = redirectBase + "/auth/callback";

        String authUrl = accountsBase + "/oauth/v2/auth"
                + "?response_type=code"
                + "&client_id=" + encode(clientId)
                + "&scope=" + encode(SCOPES)
                + "&redirect_uri=" + encode(redirectUri)
                + "&access_type=offline"
                + "&prompt=" + encode(prompt)
                + "&state=" + encode(state);

        resp.sendRedirect(authUrl);
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp)
            throws IOException {
        CorsSupport.addCorsHeaders(req, resp);
        resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
