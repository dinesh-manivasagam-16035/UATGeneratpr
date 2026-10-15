package com.uat.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class CrmConsentServlet extends HttpServlet {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        CorsSupport.applyOptions(req, resp);
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        CorsSupport.apply(req, resp);
        resp.setContentType("application/json");

        ObjectNode out = MAPPER.createObjectNode();
        boolean oauthAvailable = notEmpty(System.getenv("ZOHO_CLIENT_ID"));
        out.put("oauth_available", oauthAvailable);

        // Server-side credentials (ZOHO_REFRESH_TOKEN / Catalyst Connection) are for
        // Zoho Projects push. They used to be reported as "authorized" here, which
        // unlocked Execute without any CRM credentials and silently produced
        // simulated results. They are now reported separately.
        String envRefresh = System.getenv("ZOHO_REFRESH_TOKEN");
        String connName   = CatalystConnectionAuth.configuredConnectionName();
        out.put("projects_server", notEmpty(envRefresh) || notEmpty(connName));
        out.put("persistent_sessions", CrmTokenStore.persistent());

        // CRM is authorized only by a signed-in user session that can call CRM.
        String sid = cookieValue(req, CrmTokenStore.COOKIE);
        CrmTokenStore.TokenBundle bundle = CrmTokenStore.get(sid);
        if (bundle != null && !bundle.isExpired() && bundle.hasOAuthCredentials()) {
            out.put("authorized", true);
            out.put("source", "user_credentials");
            out.put("email", bundle.email != null ? bundle.email : "");
            resp.getWriter().write(MAPPER.writeValueAsString(out));
            return;
        }

        out.put("authorized", false);
        resp.getWriter().write(MAPPER.writeValueAsString(out));
    }

    private static String cookieValue(HttpServletRequest req, String name) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
