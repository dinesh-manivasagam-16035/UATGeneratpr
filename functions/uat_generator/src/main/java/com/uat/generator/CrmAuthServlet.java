package com.uat.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class CrmAuthServlet extends HttpServlet {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SCOPES =
            "ZohoCRM.modules.ALL,ZohoCRM.settings.ALL," +
            "ZohoProjects.projects.ALL,ZohoProjects.bugs.ALL";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String clientId = System.getenv("ZOHO_CLIENT_ID");
        if (clientId == null || clientId.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            resp.setContentType("application/json");
            ObjectNode err = MAPPER.createObjectNode();
            err.put("error", "CRM OAuth not configured – set ZOHO_CLIENT_ID env var.");
            resp.getWriter().write(MAPPER.writeValueAsString(err));
            return;
        }

        String redirectUri = buildRedirectUri(req);
        String state = UUID.randomUUID().toString();

        // Store state in cookie for CSRF validation in callback.
        Cookie stateCookie = new Cookie("tp_oauth_state", state);
        stateCookie.setPath("/");
        stateCookie.setHttpOnly(true);
        stateCookie.setMaxAge(300); // 5 minutes
        resp.addCookie(stateCookie);

        String url = "https://accounts.zoho.com/oauth/v2/auth"
                + "?response_type=code"
                + "&client_id=" + encode(clientId)
                + "&scope=" + encode(SCOPES)
                + "&redirect_uri=" + encode(redirectUri)
                + "&state=" + encode(state)
                + "&access_type=offline";

        resp.sendRedirect(url);
    }

    static String buildRedirectUri(HttpServletRequest req) {
        String scheme = req.getScheme();
        String host   = req.getServerName();
        int    port   = req.getServerPort();

        StringBuilder sb = new StringBuilder(scheme).append("://").append(host);
        boolean defaultPort = ("https".equals(scheme) && port == 443)
                           || ("http".equals(scheme)  && port == 80);
        if (!defaultPort) {
            sb.append(':').append(port);
        }
        sb.append(req.getContextPath()).append("/crm/callback");
        return sb.toString();
    }

    private static String encode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
