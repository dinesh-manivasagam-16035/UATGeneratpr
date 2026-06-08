package com.uat.generator;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@WebServlet("/auth/status")
public class AuthStatusServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        CorsSupport.addCorsHeaders(req, resp);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");

        String sid = SessionUtil.getSid(req);

        if (sid == null || !TokenStore.has(sid)) {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write("{\"authenticated\":false}");
            return;
        }

        TokenStore.TokenBundle bundle = TokenStore.get(sid);
        if (bundle == null) {
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write("{\"authenticated\":false}");
            return;
        }

        String email = bundle.email != null ? bundle.email : "";
        String userId = bundle.userId != null ? bundle.userId : "";

        resp.setStatus(HttpServletResponse.SC_OK);
        resp.getWriter().write(
            "{\"authenticated\":true,\"email\":\"" + escapeJson(email) +
            "\",\"userId\":\"" + escapeJson(userId) + "\"}"
        );
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        CorsSupport.addCorsHeaders(req, resp);
        resp.setStatus(HttpServletResponse.SC_OK);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
