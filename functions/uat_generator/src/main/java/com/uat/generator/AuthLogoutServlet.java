package com.uat.generator;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@WebServlet("/auth/logout")
public class AuthLogoutServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        CorsSupport.addCorsHeaders(req, resp);

        String sid = SessionUtil.getSid(req);
        if (sid != null) {
            TokenStore.delete(sid);

            // Expire the sid cookie
            Cookie expiredCookie = new Cookie("sid", "");
            expiredCookie.setMaxAge(0);
            expiredCookie.setPath("/");
            expiredCookie.setHttpOnly(true);
            expiredCookie.setSecure(req.isSecure());
            resp.addCookie(expiredCookie);
        }

        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.getWriter().write("{\"ok\":true}");
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        CorsSupport.addCorsHeaders(req, resp);
        resp.setStatus(HttpServletResponse.SC_OK);
    }
}
