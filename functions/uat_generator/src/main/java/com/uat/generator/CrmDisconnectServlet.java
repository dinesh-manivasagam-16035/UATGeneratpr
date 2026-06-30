package com.uat.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * POST /crm/disconnect
 *
 * Clears the CRM session token without affecting the Catalyst app login.
 * The caller's tp_crm_sid cookie is expired and the in-memory token removed.
 */
public class CrmDisconnectServlet extends HttpServlet {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        CorsSupport.apply(req, resp);
        resp.setContentType("application/json");

        String sid = cookieValue(req, "tp_crm_sid");
        CrmTokenStore.remove(sid);

        Cookie expire = new Cookie("tp_crm_sid", "");
        expire.setPath("/");
        expire.setHttpOnly(true);
        expire.setMaxAge(0);
        resp.addCookie(expire);

        ObjectNode out = MAPPER.createObjectNode();
        out.put("success", true);
        MAPPER.writeValue(resp.getWriter(), out);
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        CorsSupport.apply(req, resp);
        resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private static String cookieValue(HttpServletRequest req, String name) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
