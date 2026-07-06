package com.uat.generator;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * POST /generate
 *
 * Body: {
 *   "brd":     "Full BRD text...",
 *   "modules": ["Leads", "Deals"],   // 1..3 module api_names
 *   "module":  "Leads",              // legacy single-module form (still accepted)
 *   "project_key": "ACME-UAT"        // optional, embedded into projects_payload
 * }
 *
 * For each module the LLM is called once and the resulting cases are
 * concatenated (each case tagged with its source module). The aim is
 * 30+ cases per module so a 3-module request yields ~90-100 cases total.
 */
public class GenerateServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(GenerateServlet.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
            .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
    private static final int MAX_MODULES = 3;

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        CorsSupport.apply(req, resp);

        String llmProvider = System.getenv().getOrDefault("LLM_PROVIDER", "claude");
        String model = System.getenv().getOrDefault("COPILOT_MODEL", "gpt-4o");
        String provider = "mock".equalsIgnoreCase(llmProvider) ? "mock"
                        : "zia".equalsIgnoreCase(llmProvider)  ? "zia"
                        : "GitHub Models (" + model + ")";
        int brdLength = 0;
        List<String> modules = new ArrayList<>();

        try {
            JsonNode body = MAPPER.readTree(req.getInputStream());
            String brd = textOf(body, "brd");
            String projectKey = textOf(body, "project_key");
            String platform     = textOf(body, "platform");
            if (platform.isEmpty()) platform = "crm";
            String creatorOwner = textOf(body, "creator_owner");
            String creatorApp   = textOf(body, "creator_app");
            brdLength = brd.length();

            // Accept both `modules: [...]` and legacy `module: "Leads"`
            JsonNode mArr = body.get("modules");
            if (mArr != null && mArr.isArray()) {
                for (JsonNode m : mArr) {
                    String v = m.asText("").trim();
                    if (!v.isEmpty() && !modules.contains(v)) modules.add(v);
                    if (modules.size() >= MAX_MODULES) break;
                }
            }
            if (modules.isEmpty()) {
                String legacy = textOf(body, "module");
                if (!legacy.isEmpty()) modules.add(legacy);
            }

            if (brd.isEmpty() || modules.isEmpty()) {
                writeError(resp, HttpServletResponse.SC_BAD_REQUEST,
                        "Fields 'brd' and 'modules' (array of up to 3) are required.");
                return;
            }

            ArrayNode allCases = MAPPER.createArrayNode();
            ObjectNode perModule = MAPPER.createObjectNode();

            for (String module : modules) {
                String moduleSchema = ModuleSchemas.forModule(module);
                String llmResponse;
                if ("mock".equalsIgnoreCase(llmProvider)) {
                    llmResponse = MockLlmClient.generate(brd, module, moduleSchema);
                } else if ("zia".equalsIgnoreCase(llmProvider)) {
                    llmResponse = ZiaClient.generate(brd, module, moduleSchema);
                } else if ("creator".equalsIgnoreCase(platform)) {
                    llmResponse = ClaudeClient.generateForCreator(brd, module, creatorOwner, creatorApp);
                } else {
                    llmResponse = ClaudeClient.generate(brd, module, moduleSchema);
                }

                JsonNode cases = extractCasesArray(llmResponse);
                int count = 0;
                if (cases.isArray()) {
                    for (JsonNode tc : cases) {
                        if (!tc.isObject()) continue;
                        ObjectNode copy = ((ObjectNode) tc).deepCopy();
                        copy.put("module", module);
                        allCases.add(copy);
                        count++;
                    }
                }
                perModule.put(module, count);
                RunLogger.logGenerate(req, module, provider, brdLength, count, "generated", null);
            }

            ObjectNode out = MAPPER.createObjectNode();
            ArrayNode modulesOut = out.putArray("modules");
            modules.forEach(modulesOut::add);
            out.put("provider", provider);
            out.set("counts", perModule);
            out.put("total", allCases.size());
            out.set("cases", allCases);
            out.set("projects_payload", ProjectsPayloadBuilder.build(allCases, projectKey));

            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(MAPPER.writeValueAsString(out));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "generate failed", e);
            for (String m : modules) {
                RunLogger.logGenerate(req, m, provider, brdLength, 0, "failed", e.getMessage());
            }
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

        // Fix common LLM JSON quirks before parsing:
        // 0. Expand JS .repeat(N) calls: "A".repeat(255) → "AAA..." (capped at 500 chars)
        trimmed = expandRepeatCalls(trimmed);
        // 1. Remove sentence-ending dot outside closing quote: "value". → "value"
        trimmed = trimmed.replaceAll("\"\\.(\\s*[,}\\]\"\\n\\r])", "\"$1");
        // 2. Remove JS spread operators: ...identifier or bare ...
        trimmed = trimmed.replaceAll(",?\\s*\\.{3}\\w*\\s*,?", "");
        // 3. Collapse JS string concatenation: "a" + var + "b" → "ab" (run until stable)
        String prev;
        do {
            prev = trimmed;
            // "str" + identifier (variable, dot-path, array index)
            trimmed = trimmed.replaceAll("\"(\\s*\\+\\s*[a-zA-Z_$][\\w$\\.\\[\\]]*)", "\"");
            // "str1" + "str2" → "str1str2" (drop the closing/opening quotes around the +)
            trimmed = trimmed.replaceAll("\"(\\s*\\+\\s*)\"", "");
        } while (!trimmed.equals(prev));
        // 4. Quote unquoted dot-path values starting with a letter: ": data[0].foo.bar"
        trimmed = trimmed.replaceAll(
            ":\\s*(?!\")([a-zA-Z_][a-zA-Z0-9_]*(?:\\[[^\\]]*])?(?:\\.[a-zA-Z_][a-zA-Z0-9_]*(?:\\[[^\\]]*])?)+)([,}\\]\\s])",
            ": \"$1\"$2");
        // 5. Quote dot-starting values: ": .data[0].id" → ": \".data[0].id\""
        trimmed = trimmed.replaceAll(
            ":\\s*(?!\")(\\.(?:[a-zA-Z_$][\\w$]*(?:\\[[^\\]]*])?(?:\\.(?:[a-zA-Z_$][\\w$]*(?:\\[[^\\]]*])?))*))([,}\\]\\s\\n\\r])",
            ": \"$1\"$2");
        // 6. Remove trailing commas before } or ]
        trimmed = trimmed.replaceAll(",\\s*([}\\]])", "$1");
        // 7. Quote bare identifier followed by a trailing dot: ": step1." → ": \"step1.\""
        trimmed = trimmed.replaceAll(
            ":\\s*(?!\")([a-zA-Z_$][\\w$]*)\\.(\\s*[,}\\]\\n\\r])",
            ": \"$1.\"$2");

        try {
            JsonNode parsed = MAPPER.readTree(trimmed);
            if (parsed.isArray()) return parsed;
            if (parsed.has("cases")) return parsed.get("cases");
            if (parsed.has("test_cases")) return parsed.get("test_cases");
            return parsed;
        } catch (com.fasterxml.jackson.core.JsonParseException jpe) {
            throw new IOException(buildParseErrorContext(jpe, trimmed), jpe);
        }
    }

    private static String buildParseErrorContext(com.fasterxml.jackson.core.JsonParseException jpe, String json) {
        com.fasterxml.jackson.core.JsonLocation loc = jpe.getLocation();
        int lineNum = (int) loc.getLineNr();
        int colNum  = (int) loc.getColumnNr();
        String[] lines = json.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        sb.append("JSON parse error at line ").append(lineNum).append(", col ").append(colNum).append(". ");
        if (lineNum > 0 && lineNum <= lines.length) {
            String badLine = lines[lineNum - 1];
            String snippet = badLine.length() > 120 ? badLine.substring(0, 120) + "…" : badLine;
            sb.append("Offending line: [").append(snippet.trim()).append("]. ");
        }
        sb.append("Original: ").append(jpe.getOriginalMessage());
        return sb.toString();
    }

    private static final Pattern REPEAT_CALL =
            Pattern.compile("\"([^\"]{0,50})\"\\.repeat\\s*\\(\\s*(\\d+)\\s*\\)");

    private static String expandRepeatCalls(String text) {
        Matcher m = REPEAT_CALL.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String unit  = m.group(1);
            int    count = Math.min(Integer.parseInt(m.group(2)), 500);
            String expanded = unit.isEmpty() ? "" : unit.repeat(count);
            m.appendReplacement(sb, Matcher.quoteReplacement("\"" + expanded + "\""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static void writeError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        ObjectNode err = MAPPER.createObjectNode();
        err.put("error", message == null ? "unknown" : message);
        resp.getWriter().write(MAPPER.writeValueAsString(err));
    }
}
