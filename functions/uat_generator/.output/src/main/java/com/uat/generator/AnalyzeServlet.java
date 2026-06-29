package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /analyze
 *
 * Body: {
 *   "brd": "Full BRD text...",
 *   "modules": [ { "api_name": "Leads", "plural_label": "Leads", ... }, ... ]
 * }
 *
 * Returns: {
 *   "analysis":          "short paragraph about what the BRD is about",
 *   "suggested_modules": [ "Leads", "Custom_Module_X" ],     // top up to 3
 *   "ranking":           [ { "api_name": "Leads", "score": 12,
 *                             "matches": ["lead", "prospect"] }, ... ]
 * }
 *
 * In mock / no-LLM mode this is a keyword-frequency scorer that maps tokens
 * in the BRD onto each module's labels + a small synonym table. It catches
 * the obvious cases ("lead routing" → Leads, "ticket SLA" → Tickets) without
 * needing an LLM round-trip.
 *
 * For LLM_PROVIDER=claude the scorer is still used and the result tagged
 * "heuristic"; replacing this with a Claude call is a future enhancement.
 */
public class AnalyzeServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(AnalyzeServlet.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TOP_N = 3;

    // Hand-curated synonyms so common CRM speak maps to the right module.
    private static final Map<String, String[]> SYNONYMS = new HashMap<>();
    static {
        SYNONYMS.put("Leads",     new String[]{"lead", "prospect", "inquiry", "enquiry", "incoming"});
        SYNONYMS.put("Deals",     new String[]{"deal", "opportunity", "pipeline", "won", "closed-won", "stage", "forecast"});
        SYNONYMS.put("Contacts",  new String[]{"contact", "person", "individual"});
        SYNONYMS.put("Accounts",  new String[]{"account", "company", "organization", "organisation", "client", "customer"});
        SYNONYMS.put("Tasks",     new String[]{"task", "todo", "to-do", "assignment"});
        SYNONYMS.put("Cases",     new String[]{"case", "incident", "issue"});
        SYNONYMS.put("Tickets",   new String[]{"ticket", "support", "service request"});
        SYNONYMS.put("Products",  new String[]{"product", "sku", "catalog"});
        SYNONYMS.put("Quotes",    new String[]{"quote", "quotation", "proposal"});
        SYNONYMS.put("Vendors",   new String[]{"vendor", "supplier"});
        SYNONYMS.put("Campaigns", new String[]{"campaign", "marketing"});
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        CorsSupport.apply(req, resp);

        try {
            JsonNode body = MAPPER.readTree(req.getInputStream());
            String brd = body.path("brd").asText("");
            JsonNode modules = body.path("modules");

            if (brd.isEmpty()) {
                writeError(resp, HttpServletResponse.SC_BAD_REQUEST,
                        "Field 'brd' is required.");
                return;
            }
            if (!modules.isArray() || modules.size() == 0) {
                writeError(resp, HttpServletResponse.SC_BAD_REQUEST,
                        "Field 'modules' (array) is required.");
                return;
            }

            // Build module name list for LLM prompt
            List<String> moduleApiNames = new ArrayList<>();
            for (JsonNode m : modules) {
                String apiName = m.path("api_name").asText("");
                if (!apiName.isEmpty()) moduleApiNames.add(apiName);
            }
            String moduleNameList = String.join(", ", moduleApiNames);

            ObjectNode out = MAPPER.createObjectNode();
            String method = "heuristic";

            // Try LLM-powered analysis first (requires GITHUB_TOKEN)
            boolean llmSucceeded = false;
            if (System.getenv("GITHUB_TOKEN") != null && !System.getenv("GITHUB_TOKEN").isEmpty()) {
                try {
                    String llmRaw = ClaudeClient.analyze(brd, moduleNameList);
                    // Strip markdown fencing if model wrapped it
                    String trimmed = llmRaw.trim();
                    if (trimmed.startsWith("```")) {
                        trimmed = trimmed.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("```$", "").trim();
                    }
                    int start = trimmed.indexOf('{');
                    int end   = trimmed.lastIndexOf('}');
                    if (start >= 0 && end > start) trimmed = trimmed.substring(start, end + 1);

                    JsonNode llmOut = MAPPER.readTree(trimmed);
                    ArrayNode suggested = out.putArray("suggested_modules");
                    JsonNode llmSuggested = llmOut.path("suggested_modules");
                    if (llmSuggested.isArray()) {
                        for (JsonNode s : llmSuggested) {
                            String v = s.asText("").trim();
                            if (!v.isEmpty() && moduleApiNames.contains(v)) suggested.add(v);
                        }
                    }
                    out.put("analysis", llmOut.path("analysis").asText("Document analyzed by LLM."));
                    out.putArray("ranking"); // empty — LLM doesn't score
                    method = "llm";
                    llmSucceeded = true;
                } catch (Exception llmEx) {
                    LOG.warning("LLM analyze failed, falling back to heuristic: " + llmEx.getMessage());
                }
            }

            if (!llmSucceeded) {
                // Keyword heuristic fallback
                String lowerBrd = brd.toLowerCase(Locale.ROOT);
                List<ScoredModule> scored = new ArrayList<>();

                for (JsonNode m : modules) {
                    String apiName = m.path("api_name").asText("");
                    if (apiName.isEmpty()) continue;
                    String plural   = m.path("plural_label").asText("").toLowerCase(Locale.ROOT);
                    String singular = m.path("singular_label").asText("").toLowerCase(Locale.ROOT);

                    Set<String> tokens = new HashSet<>();
                    addTokens(tokens, apiName);
                    addTokens(tokens, plural);
                    addTokens(tokens, singular);
                    String[] syn = SYNONYMS.get(apiName);
                    if (syn != null) tokens.addAll(Arrays.asList(syn));

                    ScoredModule sm = new ScoredModule(apiName, m.path("plural_label").asText(apiName));
                    for (String t : tokens) {
                        if (t.length() < 3) continue;
                        int n = countOccurrences(lowerBrd, t);
                        if (n > 0) {
                            sm.score += n * Math.max(1, t.length() / 4);
                            sm.matches.add(t);
                        }
                    }
                    if (sm.score > 0) scored.add(sm);
                }

                scored.sort(Comparator.<ScoredModule>comparingInt(x -> -x.score)
                        .thenComparing(x -> x.apiName));

                out.put("analysis", buildAnalysis(brd, scored));
                ArrayNode suggested = out.putArray("suggested_modules");
                ArrayNode ranking   = out.putArray("ranking");
                int taken = 0;
                for (ScoredModule sm : scored) {
                    if (taken < TOP_N) { suggested.add(sm.apiName); taken++; }
                    ObjectNode r = ranking.addObject();
                    r.put("api_name", sm.apiName);
                    r.put("label",    sm.label);
                    r.put("score",    sm.score);
                    ArrayNode mArr = r.putArray("matches");
                    sm.matches.forEach(mArr::add);
                }
            }

            out.put("method", method);
            out.put("brd_length", brd.length());

            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(MAPPER.writeValueAsString(out));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "analyze failed", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        CorsSupport.applyOptions(req, resp);
    }

    private static void addTokens(Set<String> out, String s) {
        if (s == null) return;
        // Split on non-letters then lowercase. Also include the original
        // and a "depluralized" form to catch "Lead"/"Leads"/"lead/leads".
        for (String tok : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (tok.isEmpty()) continue;
            out.add(tok);
            if (tok.endsWith("s") && tok.length() > 3) out.add(tok.substring(0, tok.length() - 1));
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) return 0;
        int n = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            // Word boundary check so "leadership" doesn't match "lead"
            boolean leftOk = idx == 0
                    || !Character.isLetterOrDigit(haystack.charAt(idx - 1));
            int end = idx + needle.length();
            boolean rightOk = end >= haystack.length()
                    || !Character.isLetterOrDigit(haystack.charAt(end));
            if (leftOk && rightOk) n++;
            idx = end;
        }
        return n;
    }

    private static String buildAnalysis(String brd, List<ScoredModule> scored) {
        StringBuilder sb = new StringBuilder();
        int chars = brd.length();
        sb.append("BRD is ").append(chars).append(" chars / ~")
          .append(Math.max(1, chars / 5)).append(" tokens. ");
        if (scored.isEmpty()) {
            sb.append("No module names from your CRM appear in the BRD — pick manually based on the test scope you have in mind.");
            return sb.toString();
        }
        sb.append("Most-mentioned modules: ");
        int show = Math.min(scored.size(), TOP_N);
        for (int i = 0; i < show; i++) {
            ScoredModule sm = scored.get(i);
            if (i > 0) sb.append(", ");
            sb.append(sm.label).append(" (").append(sm.score).append(" hits)");
        }
        sb.append(". Adjust the selection below if needed.");
        return sb.toString();
    }

    private static void writeError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        ObjectNode err = MAPPER.createObjectNode();
        err.put("error", message == null ? "unknown" : message);
        resp.getWriter().write(MAPPER.writeValueAsString(err));
    }

    private static class ScoredModule {
        final String apiName;
        final String label;
        int score = 0;
        final Set<String> matches = new HashSet<>();
        ScoredModule(String apiName, String label) {
            this.apiName = apiName;
            this.label = label;
        }
    }
}
