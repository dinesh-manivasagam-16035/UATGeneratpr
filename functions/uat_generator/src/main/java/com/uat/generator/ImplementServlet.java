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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /implement — step 1 of the implementation pipeline (Spec Extractor).
 *
 * Body: {
 *   "brd":     "Full BRD text...",
 *   "modules": ["Leads", "Properties"],   // modules in scope (existing or to be created), up to 5
 *   "max_llm_calls": 6, "deadline_sec": 120,  // optional, can only lower the server limits
 *   "answers": [{"question": "...", "answer": "..."}]   // optional: client decisions from review
 * }
 *
 * Returns { spec, org, requirements, coverage, counts, warnings, provider, llm_calls }.
 * spec has the sections listed in ImplSpec; every item carries id, executor, status,
 * include and req_ids. The spec is meant to be reviewed and edited by a consultant
 * (step 2) before /implement/apply (step 3) and /implement/verify (step 4).
 *
 * How it works:
 *   1. BRD → section-aligned chunks (BrdChunker); requirements extracted (Requirements).
 *   2. When CRM is connected, the org is read (ImplOrg) so the model reuses existing
 *      modules and fields, and every item is marked "new" or "exists".
 *   3. Two calls per chunk: DATA MODEL (modules, fields, layouts, validation rules,
 *      roles, open questions) and AUTOMATION (workflows, Deluge functions, open questions),
 *      in parallel inside a deadline, with one retry for an unusable response.
 *   4. Items are merged and de-duplicated across chunks.
 *   5. One PLAN call turns the merged spec into phased tasks. If it fails or there is
 *      no time left, a plan is derived from the spec instead (and a warning says so).
 *
 * Tunables: IMPL_MAX_LLM_CALLS (9), GEN_CHUNK_CHARS (9000), GEN_MAX_CHUNKS (6),
 * GEN_CONCURRENCY (2), GEN_DEADLINE_SEC (140).
 */
public class ImplementServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(ImplementServlet.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true);
    private static final int MAX_MODULES = 5;
    private static final long MIN_TIME_FOR_RETRY_MS = 40_000;
    private static final long MIN_TIME_FOR_PLAN_MS = 25_000;

    private static final class Task {
        final String group;              // "data_model" | "automation"
        final BrdChunker.Chunk chunk;
        Task(String group, BrdChunker.Chunk chunk) { this.group = group; this.chunk = chunk; }
        List<String> sections() { return "automation".equals(group) ? ImplSpec.AUTOMATION : ImplSpec.DATA_MODEL; }
        String system() { return "automation".equals(group) ? ImplPrompts.automationSystem() : ImplPrompts.dataModelSystem(); }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        CorsSupport.apply(req, resp);

        String llmProvider = System.getenv().getOrDefault("LLM_PROVIDER", "github");
        String model = System.getenv().getOrDefault("COPILOT_MODEL", "gpt-4o");
        boolean mock = "mock".equalsIgnoreCase(llmProvider);
        String provider = mock ? "mock" : "GitHub Models (" + model + ")";
        if ("zia".equalsIgnoreCase(llmProvider)) {
            writeError(resp, HttpServletResponse.SC_NOT_IMPLEMENTED,
                    "The implementation generator doesn't support LLM_PROVIDER=zia yet. Use GitHub Models or mock.");
            return;
        }
        ExecutorService pool = null;

        try {
            JsonNode body = MAPPER.readTree(req.getInputStream());
            String brd = body.path("brd").asText("").trim();
            List<String> modules = new ArrayList<>();
            for (JsonNode m : body.path("modules")) {
                String v = m.asText("").trim();
                if (!v.isEmpty() && !modules.contains(v)) modules.add(v);
            }
            if (brd.isEmpty()) {
                writeError(resp, HttpServletResponse.SC_BAD_REQUEST, "Field 'brd' is required.");
                return;
            }
            List<String> warnings = new ArrayList<>();
            if (modules.size() > MAX_MODULES) {
                warnings.add("Only the first " + MAX_MODULES + " modules were used.");
                modules = new ArrayList<>(modules.subList(0, MAX_MODULES));
            }
            StringBuilder decisionText = new StringBuilder();
            for (JsonNode a : body.path("answers")) {
                String q = a.path("question").asText("").trim();
                String ans = a.path("answer").asText("").trim();
                if (!q.isEmpty() && !ans.isEmpty() && decisionText.length() < 3000) {
                    decisionText.append("- Q: ").append(q).append("\n  A: ").append(ans).append('\n');
                }
            }
            final String decisions = decisionText.toString().trim();
            String moduleList = modules.isEmpty() ? "(none picked — infer them from the BRD)" : String.join(", ", modules);

            // ---- 1. Chunks and requirements ----
            int chunkChars = envInt("GEN_CHUNK_CHARS", 9000);
            BrdChunker.Result split = mock
                    ? BrdChunker.chunk(brd, Integer.MAX_VALUE / 2, 1)
                    : BrdChunker.chunk(brd, chunkChars, envInt("GEN_MAX_CHUNKS", 6));
            List<BrdChunker.Chunk> chunks = split.chunks;
            if (split.truncated()) {
                warnings.add("The BRD is longer than this run can cover. Sections not used: "
                        + summarize(split.droppedHeadings) + ".");
            }
            List<Requirements.Requirement> reqs = Requirements.extract(BrdChunker.chunk(brd, chunkChars, 10_000).chunks);

            // ---- 2. Live org ----
            CrmClient crm = mock ? null : CrmMetadata.sessionClient(req);
            ImplOrg org = null;
            String orgText = "";
            if (crm != null) {
                org = ImplOrg.read(crm, modules, false);
                orgText = org.promptText(modules, 3500);
                for (String e : org.errors) warnings.add("Couldn't read org metadata — " + trim(e, 160));
            } else if (!mock) {
                warnings.add("Not connected to Zoho CRM, so nothing is checked against your org and every "
                        + "item is marked \"not compared\". Connect CRM to reuse existing fields and to apply.");
            }

            // ---- 3. Plan the calls (one call is kept back for the delivery plan) ----
            int maxCalls = Math.max(2, envInt("IMPL_MAX_LLM_CALLS", 9));
            int bodyCalls = body.path("max_llm_calls").asInt(0);
            if (bodyCalls > 0) maxCalls = Math.max(2, Math.min(maxCalls, bodyCalls));
            int deadlineSec = envInt("GEN_DEADLINE_SEC", 140);
            int bodyDeadline = body.path("deadline_sec").asInt(0);
            if (bodyDeadline > 0) deadlineSec = Math.min(deadlineSec, bodyDeadline);

            List<Task> tasks = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (BrdChunker.Chunk c : chunks) {
                for (String g : new String[]{"data_model", "automation"}) {
                    if (mock || tasks.size() < maxCalls - 1) tasks.add(new Task(g, c));
                    else skipped.add(c.label() + " (" + g.replace('_', ' ') + ")");
                }
            }
            if (!skipped.isEmpty()) {
                warnings.add("Call budget reached; not covered: " + summarize(skipped)
                        + ". Raise IMPL_MAX_LLM_CALLS or split the BRD.");
            }

            // ---- 4. Run ----
            long deadline = System.currentTimeMillis() + deadlineSec * 1000L;
            ObjectNode spec = MAPPER.createObjectNode();
            Map<String, ObjectNode> seen = new HashMap<>();
            List<String> summaries = new ArrayList<>();
            int dups = 0;
            int calls = 0;

            if (mock) {
                ImplSpec.Parsed p = ImplSpec.parse(MockImplClient.generate(brd, modules, reqs), ImplSpec.SECTIONS);
                for (String s : ImplSpec.SECTIONS) ImplSpec.merge(spec, s, p.items.get(s), seen);
                summaries.add(p.summary);
            } else {
                final String fOrgText = orgText;
                final String fModules = moduleList;
                int concurrency = Math.max(1, Math.min(envInt("GEN_CONCURRENCY", 2), tasks.size()));
                pool = Executors.newFixedThreadPool(concurrency);
                List<Future<ImplSpec.Parsed>> futures = new ArrayList<>();
                for (Task t : tasks) {
                    futures.add(pool.submit(() -> runTask(t, chunks.size(), split.outline, fModules, fOrgText,
                            Requirements.promptList(reqs, t.chunk.index + 1, 1500), decisions, deadline)));
                }
                for (int i = 0; i < futures.size(); i++) {
                    Task t = tasks.get(i);
                    String where = t.chunk.label() + " / " + t.group.replace('_', ' ');
                    ImplSpec.Parsed p;
                    try {
                        p = futures.get(i).get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                    } catch (TimeoutException te) {
                        futures.get(i).cancel(true);
                        warnings.add(where + " failed: ran out of time");
                        continue;
                    } catch (Exception e) {
                        Throwable c = e.getCause() != null ? e.getCause() : e;
                        warnings.add(where + " failed: " + trim(c.getMessage(), 200));
                        continue;
                    }
                    calls++;
                    if (!p.ok()) { warnings.add(where + " failed: " + p.error); continue; }
                    if (p.salvaged) warnings.add(where + ": response was cut off; kept " + p.size() + " complete item(s)");
                    if (p.dropped > 0) warnings.add(where + ": " + p.dropped + " malformed item(s) dropped");
                    if (!p.summary.isEmpty()) summaries.add(p.summary);
                    for (String s : t.sections()) dups += ImplSpec.merge(spec, s, p.items.get(s), seen);
                }
            }
            if (dups > 0) warnings.add(dups + " duplicate item(s) merged across BRD parts.");
            ImplSpec.finish(spec);
            markStatus(spec, org);

            // ---- 5. Delivery plan ----
            boolean planned = false;
            if (!mock && deadline - System.currentTimeMillis() > MIN_TIME_FOR_PLAN_MS && hasBuildItems(spec)) {
                try {
                    LlmResult r = ClaudeClient.chat(ImplPrompts.planSystem(),
                            ImplPrompts.planPrompt(digest(spec, 6000), allRequirements(reqs, 1500)), 0);
                    calls++;
                    ImplSpec.Parsed p = ImplSpec.parse(r.content, List.of("plan"));
                    if (p.ok() && !p.items.get("plan").isEmpty()) {
                        ImplSpec.merge(spec, "plan", p.items.get("plan"), seen);
                        planned = true;
                        if (p.salvaged || r.truncated()) warnings.add("Plan response was cut off; kept " + p.size() + " task(s).");
                    } else {
                        warnings.add("Plan generation failed (" + (p.ok() ? "no tasks" : p.error) + "); derived a plan from the spec instead.");
                    }
                } catch (Exception e) {
                    warnings.add("Plan generation failed (" + trim(e.getMessage(), 160) + "); derived a plan from the spec instead.");
                }
            } else if (!mock && hasBuildItems(spec)) {
                warnings.add("No time left for the plan call; derived a plan from the spec instead.");
            }
            if (!planned && ((ArrayNode) spec.path("plan")).size() == 0) {
                ImplSpec.merge(spec, "plan", ImplPlan.derive(spec), seen);
            }
            ImplSpec.finish(spec);

            spec.put("summary", String.join(" ", summaries).trim());
            ArrayNode scope = spec.putArray("modules_in_scope");
            modules.forEach(scope::add);

            // ---- 6. Traceability ----
            ArrayNode reqOut = MAPPER.createArrayNode();
            List<String> uncovered = new ArrayList<>();
            Map<String, Integer> perReq = new LinkedHashMap<>();
            for (Requirements.Requirement r : reqs) perReq.put(r.id, 0);
            for (String s : ImplSpec.SECTIONS) {
                if ("plan".equals(s)) continue;
                for (JsonNode item : spec.path(s)) {
                    for (JsonNode id : item.path("req_ids")) perReq.computeIfPresent(id.asText(), (k, v) -> v + 1);
                }
            }
            for (Requirements.Requirement r : reqs) {
                int n = perReq.get(r.id);
                ObjectNode o = reqOut.addObject();
                o.put("id", r.id);
                o.put("title", r.title);
                o.put("items", n);
                if (n == 0) uncovered.add(r.id);
            }
            if (!uncovered.isEmpty()) {
                warnings.add("Nothing in the spec delivers " + uncovered.size() + " requirement(s): "
                        + summarize(uncovered) + ". Check them during review.");
            }

            // ---- 7. Response ----
            ObjectNode out = MAPPER.createObjectNode();
            out.put("provider", provider);
            out.put("llm_calls", mock ? 0 : calls);
            out.put("chunks", chunks.size());
            ObjectNode orgOut = out.putObject("org");
            orgOut.put("connected", org != null);
            if (org != null) {
                orgOut.put("id", org.orgId);
                orgOut.put("name", org.orgName);
                orgOut.put("type", org.orgType);
                orgOut.put("sandbox", org.sandbox);
            }
            ObjectNode counts = out.putObject("counts");
            ObjectNode byExec = out.putObject("by_executor");
            for (String s : ImplSpec.SECTIONS) {
                counts.put(s, spec.path(s).size());
                for (JsonNode item : spec.path(s)) {
                    String ex = item.path("executor").asText();
                    byExec.put(ex, byExec.path(ex).asInt(0) + 1);
                }
            }
            out.set("requirements", reqOut);
            ObjectNode cov = out.putObject("coverage");
            cov.put("requirements", reqs.size());
            cov.put("covered", reqs.size() - uncovered.size());
            ArrayNode unc = cov.putArray("uncovered");
            uncovered.forEach(unc::add);
            ArrayNode warnOut = out.putArray("warnings");
            warnings.forEach(warnOut::add);
            out.set("spec", spec);

            if (!hasAnyItems(spec)) {
                resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                out.put("error", warnings.isEmpty() ? "No implementation items were produced." : warnings.get(warnings.size() - 1));
            } else {
                resp.setStatus(HttpServletResponse.SC_OK);
            }
            resp.getWriter().write(MAPPER.writeValueAsString(out));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "implement failed", e);
            writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        } finally {
            if (pool != null) pool.shutdownNow();
        }
    }

    private static ImplSpec.Parsed runTask(Task t, int totalParts, String outline, String modules,
                                           String orgText, String requirements, String decisions,
                                           long deadline) throws Exception {
        String user = ImplPrompts.partPrompt(modules, orgText, outline, t.chunk.text,
                t.chunk.index + 1, totalParts, requirements, decisions, "");
        LlmResult r = ClaudeClient.chat(t.system(), user, 0);
        ImplSpec.Parsed p = ImplSpec.parse(r.content, t.sections());
        if (p.ok() && (p.size() > 0 || !r.truncated())) {
            if (r.truncated()) p.salvaged = true;
            return p;
        }
        if (deadline - System.currentTimeMillis() < MIN_TIME_FOR_RETRY_MS) return p;
        String note = r.truncated()
                ? "Your previous answer was cut off by the output limit. Be concise: shorter descriptions, "
                  + "shorter Deluge, only the most important items."
                : "Your previous answer could not be used (" + trim(p.ok() ? "no items" : p.error, 200)
                  + "). Return valid JSON exactly in the requested shape.";
        LlmResult r2 = ClaudeClient.chat(t.system(), ImplPrompts.partPrompt(modules, orgText, outline,
                t.chunk.text, t.chunk.index + 1, totalParts, requirements, decisions, note), 0);
        ImplSpec.Parsed p2 = ImplSpec.parse(r2.content, t.sections());
        if (r2.truncated()) p2.salvaged = true;
        return p2;
    }

    /**
     * Sets status on every item against the live org (see ImplCompare for the meanings)
     * and the executor that follows from it.
     */
    static void markStatus(ObjectNode spec, ImplOrg org) {
        for (String s : ImplSpec.SECTIONS) {
            for (JsonNode n : spec.path(s)) {
                ObjectNode o = (ObjectNode) n;
                String status;
                switch (s) {
                    case "modules":
                        status = org == null || org.modules.isEmpty() ? "not_compared" : org.hasModule(o) ? "exists" : "new";
                        break;
                    case "fields":
                        ImplCompare.fieldStatus(o, org);
                        status = o.path("status").asText();
                        break;
                    case "roles":
                        status = org == null || org.roles.isEmpty() ? "not_compared" : org.hasRole(o.path("name").asText()) ? "exists" : "new";
                        break;
                    case "open_questions":
                        status = "open";
                        break;
                    case "plan":
                        status = "todo";
                        break;
                    default:
                        status = org == null ? "not_compared" : "new";
                }
                o.put("status", status);
                if ("exists".equals(status)) {
                    o.put("include", false);
                    o.put("executor", "none");
                } else {
                    o.put("executor", ImplSpec.executorFor(s, o));
                }
            }
        }
    }

    /** One line per item for the plan prompt. */
    static String digest(ObjectNode spec, int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (String s : ImplSpec.SECTIONS) {
            if ("plan".equals(s)) continue;
            for (JsonNode o : spec.path(s)) {
                if (!o.path("include").asBoolean(true)) continue;
                String line = "- " + o.path("id").asText() + " — " + ImplPlan.describe(s, o) + "\n";
                if (sb.length() + line.length() > maxChars) { sb.append("- … more items\n"); return sb.toString(); }
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private static String allRequirements(List<Requirements.Requirement> reqs, int maxChars) {
        StringBuilder sb = new StringBuilder();
        for (Requirements.Requirement r : reqs) {
            String line = "- " + r.id + (r.title.isEmpty() ? "" : " — " + r.title) + "\n";
            if (sb.length() + line.length() > maxChars) break;
            sb.append(line);
        }
        return sb.toString().trim();
    }

    private static boolean hasBuildItems(ObjectNode spec) {
        for (String s : ImplSpec.SECTIONS) {
            if ("plan".equals(s) || "open_questions".equals(s)) continue;
            if (spec.path(s).size() > 0) return true;
        }
        return false;
    }

    private static boolean hasAnyItems(ObjectNode spec) {
        for (String s : ImplSpec.SECTIONS) if (spec.path(s).size() > 0) return true;
        return false;
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        CorsSupport.applyOptions(req, resp);
    }

    static int envInt(String name, int def) {
        String v = System.getenv(name);
        if (v == null || v.trim().isEmpty()) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    static String summarize(List<String> items) {
        if (items.size() <= 5) return String.join(", ", items);
        return String.join(", ", items.subList(0, 5)) + " and " + (items.size() - 5) + " more";
    }

    static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    static void writeError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        ObjectNode err = MAPPER.createObjectNode();
        err.put("error", message == null ? "unknown" : message);
        resp.getWriter().write(MAPPER.writeValueAsString(err));
    }
}
