package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ReportBugServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(ReportBugServlet.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        resp.setHeader("Access-Control-Allow-Origin", "*");

        try {
            JsonNode body = MAPPER.readTree(req.getInputStream());
            String portalId = text(body, "portal_id", System.getenv("ZOHO_PROJECTS_PORTAL_ID"));
            String projectId = text(body, "project_id", System.getenv("ZOHO_PROJECTS_PROJECT_ID"));
            JsonNode tc = body.get("case");
            String observation = body.path("observation").asText("");
            String severity = body.path("severity").asText("Medium");

            if (portalId == null || projectId == null || tc == null) {
                writeError(resp, HttpServletResponse.SC_BAD_REQUEST,
                        "Fields 'portal_id', 'project_id', and 'case' are required.");
                return;
            }

            String mockMode = System.getenv().getOrDefault("LLM_PROVIDER", "claude");
            if ("mock".equalsIgnoreCase(mockMode)
                    && System.getenv("ZOHO_REFRESH_TOKEN") == null) {
                ObjectNode out = MAPPER.createObjectNode();
                out.put("status", "mock");
                out.put("bug_id", "MOCK-" + System.currentTimeMillis());
                out.put("title", tc.path("title").asText("UAT failure"));
                resp.setStatus(HttpServletResponse.SC_OK);
                resp.getWriter().write(MAPPER.writeValueAsString(out));
                return;
            }

            ObjectNode bugReq = MAPPER.createObjectNode();
            String title = "[UAT FAIL] " + tc.path("title").asText("Untitled");
            bugReq.put("title", title);
            bugReq.put("description", buildDescription(tc, observation));
            bugReq.put("severity", severity);
            bugReq.put("classification", "Bug");
            bugReq.put("steps_to_reproduce", reproSteps(tc));

            BugsClient client = BugsClient.fromEnv();
            String bugId = client.createBug(portalId, projectId, bugReq);

            ObjectNode out = MAPPER.createObjectNode();
            out.put("status", "created");
            out.put("bug_id", bugId);
            out.put("title", title);
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(MAPPER.writeValueAsString(out));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "report-bug failed", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        resp.setHeader("Access-Control-Allow-Origin", "*");
        resp.setHeader("Access-Control-Allow-Methods", "POST, OPTIONS");
        resp.setHeader("Access-Control-Allow-Headers", "Content-Type");
        resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }

    private static String text(JsonNode body, String field, String fallback) {
        JsonNode n = body.get(field);
        String v = n == null || n.isNull() ? "" : n.asText("").trim();
        if (!v.isEmpty()) return v;
        return fallback == null || fallback.isEmpty() ? null : fallback;
    }

    private static String buildDescription(JsonNode tc, String observation) {
        StringBuilder sb = new StringBuilder();
        sb.append("UAT case failed during execution.\n\n");
        sb.append("Acceptance:\n").append(tc.path("acceptance").asText("(none)")).append("\n\n");
        if (!observation.isEmpty()) {
            sb.append("Observed:\n").append(observation).append("\n\n");
        }
        String gherkin = tc.path("gherkin").asText("");
        if (!gherkin.isEmpty()) {
            sb.append("Original scenario:\n").append(gherkin);
        }
        return sb.toString();
    }

    private static String reproSteps(JsonNode tc) {
        JsonNode steps = tc.path("steps");
        if (!steps.isArray() || steps.size() == 0) return "";
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (JsonNode s : steps) {
            sb.append(i++).append(". ").append(s.path("action").asText(""))
              .append(" -> ").append(s.path("expected").asText("")).append("\n");
        }
        return sb.toString();
    }

    private static void writeError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        ObjectNode err = MAPPER.createObjectNode();
        err.put("error", message == null ? "unknown" : message);
        resp.getWriter().write(MAPPER.writeValueAsString(err));
    }
}
