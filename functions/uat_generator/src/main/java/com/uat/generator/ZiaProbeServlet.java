package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Diagnostic: POST /zia/probe {"query": "..."} sends that text to the Zia Agent through the same
 * API call LiftOff uses and returns {ok, chars, ms, reply | error, agent}. GET returns the
 * agent configuration (no secrets). Lets a failing generation be narrowed to the query text,
 * its size, or the API set-up without redeploying.
 */
public class ZiaProbeServlet extends HttpServlet {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_CHARS = 40_000;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        CorsSupport.apply(req, resp);
        resp.setContentType("application/json");
        ObjectNode out = MAPPER.createObjectNode();
        out.put("provider", Llm.label());
        out.set("agent", ZiaAgentClient.describe());
        resp.getWriter().write(MAPPER.writeValueAsString(out));
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        CorsSupport.apply(req, resp);
        resp.setContentType("application/json");
        ObjectNode out = MAPPER.createObjectNode();
        String query = "Reply with exactly: {\"ok\": true}";
        try {
            JsonNode body = MAPPER.readTree(req.getInputStream());
            if (body != null && body.hasNonNull("query")) query = body.path("query").asText();
        } catch (Exception ignored) { /* default query */ }
        if (query.length() > MAX_CHARS) query = query.substring(0, MAX_CHARS);
        out.put("chars", query.length());
        out.set("agent", ZiaAgentClient.describe());
        long t0 = System.currentTimeMillis();
        try {
            String reply = ZiaAgentClient.raw(query);
            out.put("ok", true);
            out.put("ms", System.currentTimeMillis() - t0);
            out.put("reply", reply == null ? "" : reply.length() > 2000 ? reply.substring(0, 2000) + "…" : reply);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("ms", System.currentTimeMillis() - t0);
            out.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        resp.getWriter().write(MAPPER.writeValueAsString(out));
    }
}
