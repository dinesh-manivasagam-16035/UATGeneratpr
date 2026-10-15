package com.uat.generator;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw LLM output into validated UAT case objects.
 *
 * Order of attempts:
 *   1. Plain parse (the normal path now that requests use JSON mode).
 *   2. Lenient repair of common LLM quirks (kept as a fallback for providers
 *      without JSON mode).
 *   3. Salvage: keep every complete case object from a cut-off response.
 * Every case then goes through normalize(), which drops cases with no title
 * or no steps/gherkin and coerces the other fields into the shape the client
 * and executor expect.
 */
public final class CaseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
            .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);

    private CaseParser() {}

    public static final class Parsed {
        public final List<ObjectNode> cases;
        public final int dropped;          // objects rejected by normalize()
        public final boolean salvaged;     // true when recovered from cut-off JSON
        public final String error;         // non-null when nothing usable was found

        Parsed(List<ObjectNode> cases, int dropped, boolean salvaged, String error) {
            this.cases = Collections.unmodifiableList(cases);
            this.dropped = dropped;
            this.salvaged = salvaged;
            this.error = error;
        }

        public boolean ok() { return error == null; }
    }

    public static Parsed parse(String raw) {
        String text = sliceToJson(raw == null ? "" : raw);
        if (text.isEmpty()) {
            return new Parsed(new ArrayList<>(), 0, false, "Model returned no JSON.");
        }

        String firstError;
        try {
            return fromTree(MAPPER.readTree(text), false);
        } catch (Exception e) {
            firstError = e.getMessage();
        }

        String repaired = repairCommonQuirks(text);
        try {
            return fromTree(MAPPER.readTree(repaired), false);
        } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
            firstError = jpe instanceof com.fasterxml.jackson.core.JsonParseException
                    ? parseErrorContext((com.fasterxml.jackson.core.JsonParseException) jpe, repaired)
                    : jpe.getOriginalMessage();
        } catch (Exception e) {
            firstError = e.getMessage();
        }

        List<ObjectNode> kept = new ArrayList<>();
        int dropped = 0;
        for (String obj : JsonSalvage.completeObjects(repaired)) {
            try {
                ObjectNode n = normalize(MAPPER.readTree(obj));
                if (n == null) dropped++; else kept.add(n);
            } catch (Exception e) {
                dropped++;
            }
        }
        if (!kept.isEmpty()) return new Parsed(kept, dropped, true, null);
        return new Parsed(kept, dropped, false,
                firstError == null ? "Unparseable model output." : firstError);
    }

    private static Parsed fromTree(JsonNode root, boolean salvaged) {
        JsonNode arr = root;
        if (root.isObject()) {
            if (root.has("cases")) arr = root.get("cases");
            else if (root.has("test_cases")) arr = root.get("test_cases");
        }
        List<ObjectNode> kept = new ArrayList<>();
        int dropped = 0;
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                ObjectNode c = normalize(n);
                if (c == null) dropped++; else kept.add(c);
            }
        } else if (arr.isObject()) {                // a single case object
            ObjectNode c = normalize(arr);
            if (c == null) dropped++; else kept.add(c);
        }
        if (kept.isEmpty() && dropped == 0) {
            return new Parsed(kept, 0, salvaged, "Model returned JSON without a cases array.");
        }
        return new Parsed(kept, dropped, salvaged, null);
    }

    /**
     * Validates one case and coerces it into the expected shape.
     * Returns null when the case is unusable (no title, or neither steps nor gherkin).
     */
    public static ObjectNode normalize(JsonNode node) {
        if (node == null || !node.isObject()) return null;
        ObjectNode c = ((ObjectNode) node).deepCopy();

        String title = text(c, "title");
        if (title.isEmpty()) return null;
        c.put("title", title);

        ArrayNode steps = MAPPER.createArrayNode();
        JsonNode rawSteps = c.get("steps");
        if (rawSteps != null && rawSteps.isArray()) {
            for (JsonNode s : rawSteps) {
                if (s.isObject()) {
                    String action = text(s, "action");
                    if (action.isEmpty()) continue;
                    ObjectNode st = steps.addObject();
                    st.put("action", action);
                    st.put("expected", text(s, "expected"));
                } else if (s.isTextual() && !s.asText().trim().isEmpty()) {
                    ObjectNode st = steps.addObject();
                    st.put("action", s.asText().trim());
                    st.put("expected", "");
                }
            }
        }
        String gherkin = text(c, "gherkin");
        if (steps.size() == 0 && gherkin.isEmpty()) return null;
        c.set("steps", steps);
        c.put("gherkin", gherkin);

        c.put("priority", normalizePriority(text(c, "priority")));
        c.put("acceptance", text(c, "acceptance"));
        c.put("spec_ref", text(c, "spec_ref"));
        c.put("category", text(c, "category").toLowerCase(Locale.ROOT));

        ArrayNode tags = MAPPER.createArrayNode();
        JsonNode rawTags = c.get("tags");
        if (rawTags != null && rawTags.isArray()) {
            for (JsonNode t : rawTags) {
                String v = t.asText("").trim();
                if (!v.isEmpty()) tags.add(v);
            }
        } else if (rawTags != null && rawTags.isTextual()) {
            for (String v : rawTags.asText().split(",")) {
                if (!v.trim().isEmpty()) tags.add(v.trim());
            }
        }
        c.set("tags", tags);

        ArrayNode reqIds = MAPPER.createArrayNode();
        JsonNode rawReq = c.has("req_ids") ? c.get("req_ids") : c.get("requirement_ids");
        List<String> ids = new ArrayList<>();
        if (rawReq != null && rawReq.isArray()) {
            for (JsonNode r : rawReq) ids.addAll(Requirements.idsIn(r.asText("")));
        } else if (rawReq != null && rawReq.isTextual()) {
            ids.addAll(Requirements.idsIn(rawReq.asText()));
        }
        for (String id : ids) {
            boolean dup = false;
            for (JsonNode e : reqIds) if (e.asText().equals(id)) { dup = true; break; }
            if (!dup) reqIds.add(id);
        }
        c.remove("requirement_ids");
        c.set("req_ids", reqIds);

        JsonNode plan = c.get("execution_plan");
        if (plan != null) {
            if (!plan.isArray()) {
                c.remove("execution_plan");
            } else {
                ArrayNode cleanPlan = MAPPER.createArrayNode();
                for (JsonNode step : plan) if (step.isObject()) cleanPlan.add(step);
                c.set("execution_plan", cleanPlan);
            }
        }
        return c;
    }

    /** "Requirements: FR-1, FR-2 (Region resolution)" for task/bug descriptions, or "". */
    public static String traceLine(JsonNode tc) {
        List<String> ids = new ArrayList<>();
        JsonNode r = tc.path("req_ids");
        if (r.isArray()) for (JsonNode id : r) if (!id.asText("").isEmpty()) ids.add(id.asText());
        String ref = tc.path("spec_ref").asText("").trim();
        if (ids.isEmpty() && ref.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Requirements: ");
        sb.append(ids.isEmpty() ? "(untraced)" : String.join(", ", ids));
        if (!ref.isEmpty()) sb.append(" — ").append(ref);
        return sb.toString();
    }

    private static String normalizePriority(String p) {
        String v = p.trim().toUpperCase(Locale.ROOT);
        switch (v) {
            case "P0": case "P1": case "P2": return v;
            case "CRITICAL": case "HIGH": return "P0";
            case "MEDIUM": return "P1";
            default: return "P2";
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }

    /** Drops any prose or code fences before the first { or [ and after the last } or ]. */
    private static String sliceToJson(String raw) {
        String t = raw.trim();
        int firstBrace = t.indexOf('{');
        int firstBracket = t.indexOf('[');
        int start = firstBrace == -1 ? firstBracket
                : (firstBracket == -1 ? firstBrace : Math.min(firstBrace, firstBracket));
        if (start < 0) return "";
        t = t.substring(start);
        int end = Math.max(t.lastIndexOf('}'), t.lastIndexOf(']'));
        // A cut-off response may have no closing bracket at all; keep it whole for salvage.
        if (end >= 0 && end < t.length() - 1 && !looksCutOff(t, end)) t = t.substring(0, end + 1);
        return t;
    }

    private static boolean looksCutOff(String t, int lastClose) {
        String tail = t.substring(lastClose + 1).trim();
        return tail.startsWith(",") || tail.startsWith("{") || tail.startsWith("\"");
    }

    // ---- Lenient repair (moved from GenerateServlet) ----

    private static final Pattern REPEAT_CALL =
            Pattern.compile("\"([^\"]{0,50})\"\\.repeat\\s*\\(\\s*(\\d+)\\s*\\)");

    static String repairCommonQuirks(String trimmed) {
        // 0. Expand JS .repeat(N) calls: "A".repeat(255) -> "AAA..." (capped at 500 chars)
        trimmed = expandRepeatCalls(trimmed);
        // 1. Remove sentence-ending dot outside closing quote: "value". -> "value"
        trimmed = trimmed.replaceAll("\"\\.(\\s*[,}\\]\"\\n\\r])", "\"$1");
        // 2. Remove JS spread operators: ...identifier or bare ...
        trimmed = trimmed.replaceAll(",?\\s*\\.{3}\\w*\\s*,?", "");
        // 3. Collapse JS string concatenation (run until stable)
        String prev;
        do {
            prev = trimmed;
            trimmed = trimmed.replaceAll("\"(\\s*\\+\\s*[a-zA-Z_$][\\w$\\.\\[\\]]*)", "\"");
            trimmed = trimmed.replaceAll("\"(\\s*\\+\\s*)\"", "");
        } while (!trimmed.equals(prev));
        // 4. Quote unquoted dot-path values: ": data[0].foo.bar"
        trimmed = trimmed.replaceAll(
            ":\\s*(?!\")([a-zA-Z_][a-zA-Z0-9_]*(?:\\[[^\\]]*])?(?:\\.[a-zA-Z_][a-zA-Z0-9_]*(?:\\[[^\\]]*])?)+)([,}\\]\\s])",
            ": \"$1\"$2");
        // 5. Quote dot-starting values: ": .data[0].id"
        trimmed = trimmed.replaceAll(
            ":\\s*(?!\")(\\.(?:[a-zA-Z_$][\\w$]*(?:\\[[^\\]]*])?(?:\\.(?:[a-zA-Z_$][\\w$]*(?:\\[[^\\]]*])?))*))([,}\\]\\s\\n\\r])",
            ": \"$1\"$2");
        // 6. Remove trailing commas before } or ]
        trimmed = trimmed.replaceAll(",\\s*([}\\]])", "$1");
        // 7. Quote bare identifier followed by a trailing dot: ": step1."
        trimmed = trimmed.replaceAll(
            ":\\s*(?!\")([a-zA-Z_$][\\w$]*)\\.(\\s*[,}\\]\\n\\r])",
            ": \"$1.\"$2");
        return trimmed;
    }

    private static String expandRepeatCalls(String text) {
        Matcher m = REPEAT_CALL.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String unit = m.group(1);
            int count = Math.min(Integer.parseInt(m.group(2)), 500);
            String expanded = unit.isEmpty() ? "" : unit.repeat(count);
            m.appendReplacement(sb, Matcher.quoteReplacement("\"" + expanded + "\""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String parseErrorContext(com.fasterxml.jackson.core.JsonParseException jpe, String json) {
        com.fasterxml.jackson.core.JsonLocation loc = jpe.getLocation();
        int lineNum = (int) loc.getLineNr();
        int colNum = (int) loc.getColumnNr();
        String[] lines = json.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        sb.append("JSON parse error at line ").append(lineNum).append(", col ").append(colNum).append(". ");
        if (lineNum > 0 && lineNum <= lines.length) {
            String bad = lines[lineNum - 1];
            String snippet = bad.length() > 120 ? bad.substring(0, 120) + "…" : bad;
            sb.append("Offending line: [").append(snippet.trim()).append("]. ");
        }
        sb.append("Original: ").append(jpe.getOriginalMessage());
        return sb.toString();
    }
}
