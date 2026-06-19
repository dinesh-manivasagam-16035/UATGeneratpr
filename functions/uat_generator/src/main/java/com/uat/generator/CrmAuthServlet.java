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
            "ZohoProjects.portals.READ,ZohoProjects.projects.ALL," +
            "ZohoProjects.tasks.ALL,ZohoProjects.bugs.ALL," +
            "AaaServer.profile.READ";

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

        String redirectUri  = buildRedirectUri(req);
        String oauthState   = UUID.randomUUID().toString();
        String accountsBase = accountsBase();

        // Store state in cookie for CSRF validation in callback.
        Cookie stateCookie = new Cookie("tp_oauth_state", oauthState);
        stateCookie.setPath("/");
        stateCookie.setHttpOnly(true);
        stateCookie.setMaxAge(300); // 5 minutes
        resp.addCookie(stateCookie);

        // ?switch=true forces Zoho's org-picker so the user can select a different org.
        boolean switchOrg = "true".equalsIgnoreCase(req.getParameter("switch"));
        String url = accountsBase + "/oauth/v2/auth"
                + "?response_type=code"
                + "&client_id=" + encode(clientId)
                + "&scope=" + encode(SCOPES)
                + "&redirect_uri=" + encode(redirectUri)
                + "&state=" + encode(oauthState)
                + "&access_type=offline"
                + (switchOrg ? "&prompt=select_account" : "");

        resp.sendRedirect(url);
    }

    /** Returns the Zoho accounts base URL — configurable via ZOHO_ACCOUNTS_BASE env var. */
    static String accountsBase() {
        String env = System.getenv("ZOHO_ACCOUNTS_BASE");
        if (env != null && !env.trim().isEmpty()) return env.trim();
        String dc = System.getenv("ZOHO_DC");
        if (dc == null || dc.trim().isEmpty() || dc.trim().equalsIgnoreCase("com")) {
            return "https://accounts.zoho.com";
        }
        return "https://accounts.zoho." + dc.trim().toLowerCase();
    }

    /** Returns the Zoho API base URL derived from ZOHO_DC env var. */
    static String apiBase() {
        String dc = System.getenv("ZOHO_DC");
        if (dc == null || dc.trim().isEmpty() || dc.trim().equalsIgnoreCase("com")) {
            return "https://www.zohoapis.com";
        }
        switch (dc.trim().toLowerCase()) {
            case "in": return "https://www.zohoapis.in";
            case "eu": return "https://www.zohoapis.eu";
            case "au": return "https://www.zohoapis.com.au";
            case "jp": return "https://www.zohoapis.jp";
            default:   return "https://www.zohoapis.com";
        }
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
