package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.logging.Level;
import java.util.logging.Logger;

public class GenerateServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(GenerateServlet.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        CorsSupport.apply(req, resp);

        String module = "";
        String provider = System.getenv().getOrDefault("LLM_PROVIDER", "claude");
        int brdLength = 0;

        try {
            JsonNode body = MAPPER.readTree(req.getInputStream());
            String brd = textOf(body, "brd");
            module = textOf(body, "module");
            String projectKey = textOf(body, "project_key");
            brdLength = brd.length();

            if (brd.isEmpty() || module.isEmpty()) {
                writeError(resp, HttpServletResponse.SC_BAD_REQUEST,
                        "Fields 'brd' and 'module' are required.");
                return;
            }

            String moduleSchema = ModuleSchemas.forModule(module);

            String llmResponse;
            if ("mock".equalsIgnoreCase(provider)) {
                llmResponse = MockLlmClient.generate(brd, module, moduleSchema);
            } else if ("zia".equalsIgnoreCase(provider)) {
                llmResponse = ZiaClient.generate(brd, module, moduleSchema);
            } else {
                llmResponse = ClaudeClient.generate(brd, module, moduleSchema);
            }

            JsonNode cases = extractCasesArray(llmResponse);
            int caseCount = cases.isArray() ? cases.size() : 0;
            ObjectNode out = MAPPER.createObjectNode();
            out.put("module", module);
            out.put("provider", provider);
            out.set("cases", cases);
            out.set("projects_payload", ProjectsPayloadBuilder.build(cases, projectKey));

            RunLogger.logGenerate(req, module, provider, brdLength, caseCount, "generated", null);

            resp.setStatus(HttpServletResponse.SC_OK);
            PrintWriter w = resp.getWriter();
            w.write(MAPPER.writeValueAsString(out));
            w.flush();
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "generate failed", e);
            RunLogger.logGenerate(req, module, provider, brdLength, 0, "failed", e.getMessage());
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        CorsSupport.applyOptions(req, resp);
    }

    private static String textOf(JsonNode body, String field) {
        JsonNode n = body.get(field);
        return n == null || n.isNull() ? "" : n.asText("").trim();
    }

    private static JsonNode extractCasesArray(String llmResponse) throws IOException {
        String trimmed = llmResponse.trim();
        int firstBrace = trimmed.indexOf('{');
        int firstBracket = trimmed.indexOf('[');
        int start = firstBrace == -1 ? firstBracket
                : (firstBracket == -1 ? firstBrace : Math.min(firstBrace, firstBracket));
        if (start > 0) trimmed = trimmed.substring(start);
        int lastBrace = trimmed.lastIndexOf('}');
        int lastBracket = trimmed.lastIndexOf(']');
        int end = Math.max(lastBrace, lastBracket);
        if (end >= 0 && end < trimmed.length() - 1) trimmed = trimmed.substring(0, end + 1);

        JsonNode parsed = MAPPER.readTree(trimmed);
        if (parsed.isArray()) return parsed;
        if (parsed.has("cases")) return parsed.get("cases");
        if (parsed.has("test_cases")) return parsed.get("test_cases");
        return parsed;
    }

    private static void writeError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        ObjectNode err = MAPPER.createObjectNode();
        err.put("error", message == null ? "unknown" : message);
        resp.getWriter().write(MAPPER.writeValueAsString(err));
    }
}
