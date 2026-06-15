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

        // Check server-side credentials first.
        String envRefresh = System.getenv("ZOHO_REFRESH_TOKEN");
        String connName   = CatalystConnectionAuth.configuredConnectionName();
        if (notEmpty(envRefresh) || notEmpty(connName)) {
            out.put("authorized", true);
            out.put("source", "server");
            out.putNull("email");
            resp.getWriter().write(MAPPER.writeValueAsString(out));
            return;
        }

        // Check user-specific CRM session cookie.
        String sid = cookieValue(req, "tp_crm_sid");
        CrmTokenStore.TokenBundle bundle = CrmTokenStore.get(sid);
        if (bundle != null && !bundle.isExpired()) {
            out.put("authorized", true);
            out.put("source", "user");
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
