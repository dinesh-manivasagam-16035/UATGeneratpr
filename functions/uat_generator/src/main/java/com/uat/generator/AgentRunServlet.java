package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /agent/run — the one tool a Zia Agent needs to run UAT with LiftOff.
 *
 * Body: {
 *   "brd": "...",                        required
 *   "modules": ["Leads"],                required, 1..3 CRM module api names
 *   "mode": "dry_run" | "execute" | "generate_only",   default "dry_run"
 *   "time_budget_sec": 55                default AGENT_TIME_BUDGET_SEC or 55; 20..280
 * }
 *
 * Auth: a Zoho CRM OAuth token from the agent's connection (see AgentAuth).
 *
 * It runs /generate and then /execute in-process within the time budget, and
 * returns a compact summary (coverage per requirement, failures, plan issues)
 * instead of the full case JSON. Small agent models handle a short summary
 * reliably; they don't handle 30 cases of nested JSON. "execute" creates and
 * deletes real records in the connected org, so use it only on a test org.
 */
public class AgentRunServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(AgentRunServlet.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_LISTED = 15;

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        long start = System.currentTimeMillis();

        CrmClient crm;
        try {
            crm = AgentAuth.authenticate(req);
        } catch (AgentAuth.Denied d) {
            writeError(resp, d.status, d.getMessage());
            return;
        }
        req.setAttribute(AgentAuth.ATTR, crm);

        try {
            JsonNode body = MAPPER.readTree(req.getInputStream());
            String brd = body.path("brd").asText("").trim();
            List<String> modules = new ArrayList<>();
            JsonNode m = body.get("modules");
            if (m != null && m.isArray()) {
                for (JsonNode x : m) if (!x.asText("").trim().isEmpty()) modules.add(x.asText().trim());
            } else if (m != null && m.isTextual()) {
                for (String x : m.asText().split(",")) if (!x.trim().isEmpty()) modules.add(x.trim());
            }
            if (brd.isEmpty() || modules.isEmpty()) {
                writeError(resp, 400, "Both 'brd' (the requirement text) and 'modules' (e.g. [\"Leads\"]) are required.");
                return;
            }
            String mode = body.path("mode").asText("dry_run").trim().toLowerCase();
            if (!mode.equals("execute") && !mode.equals("generate_only")) mode = "dry_run";

            int defaultBudget = envInt("AGENT_TIME_BUDGET_SEC", 55);
            int budget = clamp(body.path("time_budget_sec").asInt(defaultBudget), 20, 280);
            long end = start + budget * 1000L;

            // ---- 1. Generate (most of the budget) ----
            int genSec = mode.equals("generate_only") ? budget - 5 : (int) (budget * 0.7);
            ObjectNode genBody = MAPPER.createObjectNode();
            genBody.put("brd", brd);
            ArrayNode mods = genBody.putArray("modules");
            modules.forEach(mods::add);
            genBody.put("platform", "crm");
            genBody.put("deadline_sec", Math.max(10, genSec));
            if (body.path("max_llm_calls").asInt(0) > 0) genBody.put("max_llm_calls", body.path("max_llm_calls").asInt(0));

            Captured gen = invoke(new GenerateServlet(), req, resp, genBody);
            if (gen.status >= 400 || gen.json == null) {
                writeError(resp, 502, "Generation failed: " + errorOf(gen));
                return;
            }
            JsonNode cases = gen.json.path("cases");
            List<String> warnings = new ArrayList<>();
            for (JsonNode w : gen.json.path("warnings")) warnings.add(w.asText());

            // ---- 2. Dry run or execute (what's left of the budget) ----
            boolean ran = false;
            if (!mode.equals("generate_only") && cases.isArray() && cases.size() > 0) {
                long left = end - System.currentTimeMillis();
                if (left > 8_000) {
                    ObjectNode exBody = MAPPER.createObjectNode();
                    exBody.set("cases", cases);
                    exBody.put("platform", "crm");
                    exBody.put("dry_run", mode.equals("dry_run"));
                    exBody.put("deadline_ms", end - 3_000);
                    Captured ex = invoke(new ExecuteServlet(), req, resp, exBody);
                    if (ex.status >= 400 || ex.json == null) {
                        warnings.add("Execution failed: " + errorOf(ex));
                    } else {
                        cases = ex.json.path("cases");
                        ran = true;
                    }
                } else {
                    warnings.add("No time left to " + (mode.equals("execute") ? "execute" : "dry-run")
                            + " the cases. Raise time_budget_sec or pick fewer modules.");
                }
            }

            ObjectNode out = summarize(gen.json, cases, mode, ran, warnings);
            out.put("duration_ms", System.currentTimeMillis() - start);
            resp.setStatus(200);
            resp.getWriter().write(MAPPER.writeValueAsString(out));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "agent run failed", e);
            writeError(resp, 500, e.getMessage());
        }
    }

    // ---- Summary ----

    private static ObjectNode summarize(JsonNode gen, JsonNode cases, String mode, boolean ran,
                                        List<String> warnings) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("mode", mode);
        out.put("executed", ran && mode.equals("execute"));
        out.set("modules", gen.path("modules"));
        out.put("provider", gen.path("provider").asText(""));
        out.set("schema_source", gen.path("schema_source"));

        int total = 0, passed = 0, failed = 0, skipped = 0, planOk = 0, planIssues = 0;
        Map<String, int[]> perReq = new LinkedHashMap<>();   // total, passed, failed, plan issues
        for (JsonNode r : gen.path("requirements")) perReq.put(r.path("id").asText(), new int[4]);

        ArrayNode failures = MAPPER.createArrayNode();
        ArrayNode issues = MAPPER.createArrayNode();
        ArrayNode list = MAPPER.createArrayNode();

        for (JsonNode c : cases) {
            if ("Default API smoke test".equals(c.path("spec_ref").asText())) continue;
            total++;
            JsonNode res = c.path("execution_result");
            String status = res.path("status").asText(ran ? "skipped" : "not_run");
            boolean issue = "dry_run".equals(status) && !res.path("plan_ok").asBoolean(false);
            switch (status) {
                case "pass": passed++; break;
                case "fail": failed++; break;
                case "dry_run": if (issue) planIssues++; else planOk++; break;
                default: skipped++;
            }
            for (JsonNode id : c.path("req_ids")) {
                int[] t = perReq.get(id.asText());
                if (t == null) continue;
                t[0]++;
                if ("pass".equals(status)) t[1]++;
                if ("fail".equals(status)) t[2]++;
                if (issue) t[3]++;
            }
            if ("fail".equals(status) && failures.size() < MAX_LISTED) {
                ObjectNode f = failures.addObject();
                f.put("title", c.path("title").asText());
                f.put("module", c.path("module").asText());
                f.set("req_ids", c.path("req_ids"));
                f.put("priority", c.path("priority").asText());
                f.put("first_failure", c.path("execution_result").path("first_failure").asText(""));
            }
            if (issue && issues.size() < MAX_LISTED) {
                ObjectNode f = issues.addObject();
                f.put("title", c.path("title").asText());
                f.put("first_issue", res.path("first_issue").asText(""));
            }
            if (list.size() < 40) {
                ObjectNode s = list.addObject();
                s.put("title", c.path("title").asText());
                s.put("module", c.path("module").asText());
                s.put("priority", c.path("priority").asText());
                s.set("req_ids", c.path("req_ids"));
                s.put("status", status);
            }
        }

        ArrayNode reqs = out.putArray("requirements");
        List<String> uncovered = new ArrayList<>();
        for (JsonNode r : gen.path("requirements")) {
            String id = r.path("id").asText();
            int[] t = perReq.get(id);
            String st;
            if (t[0] == 0) { st = r.path("in_run").asBoolean(true) ? "no_cases" : "not_in_run"; uncovered.add(id); }
            else if (t[2] > 0) st = "failing";
            else if (t[3] > 0) st = "plan_issues";
            else if (t[1] == t[0]) st = "passing";
            else if (t[1] > 0) st = "partly_passing";
            else st = "not_run";
            ObjectNode o = reqs.addObject();
            o.put("id", id);
            o.put("title", r.path("title").asText());
            o.put("cases", t[0]);
            o.put("status", st);
        }

        ObjectNode totals = out.putObject("totals");
        totals.put("cases", total);
        totals.put("passed", passed);
        totals.put("failed", failed);
        totals.put("skipped", skipped);
        if (mode.equals("dry_run")) {
            totals.put("plans_ok", planOk);
            totals.put("plans_with_issues", planIssues);
        }
        out.set("failures", failures);
        if (mode.equals("dry_run")) out.set("plan_issues", issues);
        out.set("cases", list);

        ArrayNode w = out.putArray("warnings");
        for (int i = 0; i < warnings.size() && i < 10; i++) w.add(warnings.get(i));

        ArrayNode next = out.putArray("next_steps");
        if (!uncovered.isEmpty()) next.add("Requirements without cases: " + String.join(", ", uncovered)
                + ". Check the BRD describes testable behaviour for them, then run again.");
        if (planIssues > 0) next.add(planIssues + " case plan(s) have problems; they would not run correctly.");
        if (mode.equals("dry_run") && planIssues == 0 && total > 0)
            next.add("Plans look runnable. Run with mode \"execute\" on a test org to get real results.");
        if (failed > 0) next.add("Create a bug in Zoho Projects for each failure.");
        if (total > list.size()) next.add("Showing " + list.size() + " of " + total + " cases; open LiftOff for the full list.");
        return out;
    }

    // ---- In-process call helpers ----

    private static final class Captured {
        int status = 200;
        JsonNode json;
        String raw = "";
    }

    private static Captured invoke(HttpServlet servlet, HttpServletRequest req, HttpServletResponse resp,
                                   JsonNode body) throws Exception {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        BodyRequest wreq = new BodyRequest(req, bytes);
        CaptureResponse wresp = new CaptureResponse(resp);
        if (servlet instanceof GenerateServlet) ((GenerateServlet) servlet).doPost(wreq, wresp);
        else if (servlet instanceof ExecuteServlet) ((ExecuteServlet) servlet).doPost(wreq, wresp);
        else throw new IllegalArgumentException("unsupported servlet");
        Captured c = new Captured();
        c.status = wresp.status;
        c.raw = wresp.buffer.toString();
        try { c.json = MAPPER.readTree(c.raw); } catch (Exception ignored) { c.json = null; }
        return c;
    }

    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BodyRequest(HttpServletRequest req, byte[] body) { super(req); this.body = body; }
        @Override public String getMethod() { return "POST"; }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public String getContentType() { return "application/json"; }
        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return in.read(); }
                @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener l) { throw new UnsupportedOperationException(); }
            };
        }
    }

    private static final class CaptureResponse extends HttpServletResponseWrapper {
        final StringWriter buffer = new StringWriter();
        private final PrintWriter writer = new PrintWriter(buffer);
        int status = 200;
        CaptureResponse(HttpServletResponse resp) { super(resp); }
        @Override public PrintWriter getWriter() { return writer; }
        @Override public void setStatus(int sc) { status = sc; }
        @Override public int getStatus() { return status; }
        @Override public void sendError(int sc) { status = sc; }
        @Override public void sendError(int sc, String msg) { status = sc; buffer.write("{\"error\":" + quote(msg) + "}"); }
        @Override public void setContentType(String type) { /* the outer response is already JSON */ }
        @Override public void addHeader(String name, String value) { /* inner calls must not set cookies */ }
        @Override public void setHeader(String name, String value) { /* nor CORS headers on the agent call */ }
    }

    private static String quote(String s) {
        try { return MAPPER.writeValueAsString(s == null ? "" : s); } catch (Exception e) { return "\"\""; }
    }

    private static String errorOf(Captured c) {
        if (c.json != null && c.json.has("error")) return c.json.path("error").asText();
        return "HTTP " + c.status;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static int envInt(String name, int def) {
        String v = System.getenv(name);
        if (v == null || v.trim().isEmpty()) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static void writeError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        ObjectNode err = MAPPER.createObjectNode();
        err.put("error", message == null ? "unknown" : message);
        resp.getWriter().write(MAPPER.writeValueAsString(err));
    }
}
