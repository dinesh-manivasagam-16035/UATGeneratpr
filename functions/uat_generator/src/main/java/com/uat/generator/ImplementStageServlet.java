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
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /implement/stage — the blueprint generation of /implement, split into requests that each
 * fit Catalyst's ~30 s limit for Advanced I/O functions (a whole run makes several LLM calls and
 * is cut off with EXECUTION_TIME_EXCEEDED / HTTP 408). The browser drives the stages:
 *
 *   {"stage":"start",  brd, modules, answers?}
 *       → {parts:[{part, label, groups:[...]}], org_text, org, warnings, provider}   no LLM call
 *   {"stage":"part",   brd, modules, part, group, org_text, answers?}
 *       → {items:{section:[...]}, summary, salvaged, dropped, error?}               one LLM call
 *   {"stage":"finish", brd, modules, results:[{part, group, items, summary, error?}], warnings?}
 *       → the /implement response without the plan, plus needs_plan               no LLM call
 *   {"stage":"plan",   spec, requirements:[{id,title}]}
 *       → {spec, warnings}                                                            one LLM call
 *
 * Background mode (the agent's replies take longer than one request allows):
 *   {"stage":"submit", brd, modules, answers?, org_text, jobs:[{part, group}]}
 *       → {job_key, job_id}       queues the impl_worker job function (ImplJob), which makes
 *                                 the part and plan calls and stores results in Cache (JobStore)
 *   {"stage":"status", job_key}  → {state: queued|running|done|failed, done, total, label?, error?}
 *   {"stage":"finish", brd, modules, job_key, warnings?}   reads the job's results and plan
 *
 * Every stage is stateless: the BRD is re-chunked the same way each time (same env), and the
 * browser carries the org text and part results between calls.
 *
 * Tunables: IMPL_CHUNK_CHARS (6000, smaller than one-shot so each call stays short),
 * GEN_MAX_CHUNKS (6), IMPL_MAX_LLM_CALLS (13), IMPL_CALL_DEADLINE_SEC (26).
 */
public class ImplementStageServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(ImplementStageServlet.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true);
    private static final int MAX_MODULES = 5;

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        CorsSupport.apply(req, resp);
        try {
            if (!Llm.mock() && !Llm.available()) {
                ImplementServlet.writeError(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, Llm.notConfiguredMessage());
                return;
            }
            JsonNode body = MAPPER.readTree(req.getInputStream());
            String stage = body.path("stage").asText("");
            ObjectNode out;
            switch (stage) {
                case "start":  out = start(req, body); break;
                case "part":   out = part(body); break;
                case "finish": out = finish(req, body); break;
                case "plan":   out = plan(body); break;
                case "submit": out = submit(body); break;
                case "status": out = status(body); break;
                default:
                    ImplementServlet.writeError(resp, 400, "Field 'stage' must be start, part, submit, status, finish or plan.");
                    return;
            }
            // A part's or a job's own failure is data for the caller, not a failed request.
            boolean reported = "part".equals(stage) || ("status".equals(stage) && out.has("state"));
            if (out.has("error") && !reported) resp.setStatus(out.path("status").asInt(400));
            else resp.setStatus(200);
            out.remove("status");
            resp.getWriter().write(MAPPER.writeValueAsString(out));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "implement/stage failed", e);
            ImplementServlet.writeError(resp, 500, e.getMessage());
        }
    }

    // ---- inputs shared by the stages ----

    private static List<String> modules(JsonNode body) {
        List<String> m = new ArrayList<>();
        for (JsonNode n : body.path("modules")) {
            String v = n.asText("").trim();
            if (!v.isEmpty() && !m.contains(v) && m.size() < MAX_MODULES) m.add(v);
        }
        return m;
    }

    private static String moduleList(List<String> modules) {
        return modules.isEmpty() ? "(none picked — infer them from the BRD)" : String.join(", ", modules);
    }

    private static BrdChunker.Result split(String brd) {
        return BrdChunker.chunk(brd, ImplementServlet.envInt("IMPL_CHUNK_CHARS", 6000),
                ImplementServlet.envInt("GEN_MAX_CHUNKS", 6));
    }

    static List<Requirements.Requirement> requirements(String brd) {
        return Requirements.extract(BrdChunker.chunk(brd, ImplementServlet.envInt("IMPL_CHUNK_CHARS", 6000), 10_000).chunks);
    }

    private static String decisions(JsonNode body) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode a : body.path("answers")) {
            String q = a.path("question").asText("").trim();
            String ans = a.path("answer").asText("").trim();
            if (!q.isEmpty() && !ans.isEmpty() && sb.length() < 3000) sb.append("- Q: ").append(q).append("\n  A: ").append(ans).append('\n');
        }
        return sb.toString().trim();
    }

    private static ObjectNode error(int status, String msg) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("status", status);
        o.put("error", msg);
        return o;
    }

    // ---- stages ----

    private static ObjectNode start(HttpServletRequest req, JsonNode body) {
        String brd = body.path("brd").asText("").trim();
        if (brd.isEmpty()) return error(400, "Field 'brd' is required.");
        List<String> modules = modules(body);
        List<String> warnings = new ArrayList<>();
        BrdChunker.Result split = split(brd);
        if (split.truncated()) {
            warnings.add("The BRD is longer than this run can cover. Sections not used: "
                    + ImplementServlet.summarize(split.droppedHeadings) + ".");
        }

        ObjectNode out = MAPPER.createObjectNode();
        out.put("provider", Llm.label());
        CrmClient crm = Llm.mock() ? null : CrmMetadata.sessionClient(req);
        ObjectNode orgOut = out.putObject("org");
        orgOut.put("connected", crm != null);
        String orgText = "";
        if (crm != null) {
            ImplOrg org = ImplOrg.read(crm, modules, false);
            orgText = org.promptText(modules, 3500);
            orgOut.put("id", org.orgId);
            orgOut.put("name", org.orgName);
            orgOut.put("type", org.orgType);
            orgOut.put("sandbox", org.sandbox);
            for (String e : org.errors) warnings.add("Couldn't read org metadata — " + ImplementServlet.trim(e, 160));
        } else if (!Llm.mock()) {
            warnings.add("Not connected to Zoho CRM, so nothing is checked against your org and every "
                    + "item is marked \"not compared\". Connect CRM to reuse existing fields and to apply.");
        }
        out.put("org_text", orgText);

        // One call per (part, group), one kept back for the plan.
        int budget = Math.max(2, ImplementServlet.envInt("IMPL_MAX_LLM_CALLS", 13)) - 1;
        ArrayNode parts = out.putArray("parts");
        List<String> skipped = new ArrayList<>();
        int used = 0;
        for (BrdChunker.Chunk c : split.chunks) {
            ObjectNode p = MAPPER.createObjectNode();
            p.put("part", c.index + 1);
            p.put("label", c.label());
            ArrayNode groups = p.putArray("groups");
            for (String g : new String[]{"data_model", "automation"}) {
                if (Llm.mock() || used < budget) { groups.add(g); used++; }
                else skipped.add(c.label() + " (" + g.replace('_', ' ') + ")");
            }
            if (groups.size() > 0) parts.add(p);
        }
        if (!skipped.isEmpty()) {
            warnings.add("Call budget reached; not covered: " + ImplementServlet.summarize(skipped)
                    + ". Raise IMPL_MAX_LLM_CALLS or split the BRD.");
        }
        if (Llm.mock()) {                     // mock has one offline "part" covering everything
            parts.removeAll();
            parts.addObject().put("part", 1).put("label", "BRD").putArray("groups").add("mock");
        }
        ArrayNode w = out.putArray("warnings");
        warnings.forEach(w::add);
        return out;
    }

    static ObjectNode part(JsonNode body) {
        String brd = body.path("brd").asText("").trim();
        List<String> modules = modules(body);
        String group = body.path("group").asText("data_model");
        ObjectNode out = MAPPER.createObjectNode();
        out.put("part", body.path("part").asInt(1));
        out.put("group", group);
        ImplSpec.Parsed p;
        try {
            if ("mock".equals(group) || Llm.mock()) {
                p = ImplSpec.parse(MockImplClient.generate(brd, modules, requirements(brd)), ImplSpec.SECTIONS);
            } else {
                BrdChunker.Result split = split(brd);
                int idx = body.path("part").asInt(1) - 1;
                if (idx < 0 || idx >= split.chunks.size()) return error(400, "No part " + (idx + 1) + " in this BRD.");
                BrdChunker.Chunk chunk = split.chunks.get(idx);
                ImplementServlet.Task t = new ImplementServlet.Task(group, chunk);
                List<Requirements.Requirement> reqs = requirements(brd);
                int sec = body.path("deadline_sec").asInt(0) > 0 ? body.path("deadline_sec").asInt()
                        : ImplementServlet.envInt("IMPL_CALL_DEADLINE_SEC", 26);
                long deadline = System.currentTimeMillis() + sec * 1000L;
                p = ImplementServlet.runTask(t, split.chunks.size(), split.outline, moduleList(modules),
                        body.path("org_text").asText(""), Requirements.promptList(reqs, chunk.index + 1, 1500),
                        decisions(body), deadline);
            }
        } catch (Exception e) {
            out.put("error", ImplementServlet.trim(e.getMessage(), 300));
            return out;
        }
        if (!p.ok()) out.put("error", p.error);
        out.put("summary", p.summary);
        out.put("salvaged", p.salvaged);
        out.put("dropped", p.dropped);
        ObjectNode items = out.putObject("items");
        p.items.forEach((section, list) -> {
            ArrayNode arr = items.putArray(section);
            list.forEach(arr::add);
        });
        return out;
    }

    /** Merges part results (in order) into a spec; adds per-part notes to warnings. */
    static ObjectNode merge(List<JsonNode> results, List<String> warnings) {
        ObjectNode spec = MAPPER.createObjectNode();
        Map<String, ObjectNode> seen = new HashMap<>();
        List<String> summaries = new ArrayList<>();
        int dups = 0;
        for (JsonNode r : results) {
            String where = "Part " + r.path("part").asInt() + " / " + r.path("group").asText("").replace('_', ' ');
            if (r.hasNonNull("error") && !r.path("error").asText("").isEmpty()) {
                warnings.add(where + " failed: " + r.path("error").asText());
            }
            if (r.path("salvaged").asBoolean(false)) warnings.add(where + ": response was cut off; kept the complete items");
            if (r.path("dropped").asInt(0) > 0) warnings.add(where + ": " + r.path("dropped").asInt() + " malformed item(s) dropped");
            if (!r.path("summary").asText("").isEmpty()) summaries.add(r.path("summary").asText());
            JsonNode items = r.path("items");
            for (String s : ImplSpec.SECTIONS) {
                List<ObjectNode> list = new ArrayList<>();
                for (JsonNode n : items.path(s)) {
                    // Items came back from the browser or the cache: normalise them again rather than trust them.
                    if (n.isObject()) {
                        ObjectNode norm = ImplSpec.normalize(s, (ObjectNode) n);
                        if (norm != null) list.add(norm);
                    }
                }
                if (!list.isEmpty()) dups += ImplSpec.merge(spec, s, list, seen);
            }
        }
        if (dups > 0) warnings.add(dups + " duplicate item(s) merged across BRD parts.");
        ImplSpec.finish(spec);
        spec.put("summary", String.join(" ", summaries).trim());
        return spec;
    }

    private static ObjectNode finish(HttpServletRequest req, JsonNode body) throws Exception {
        String brd = body.path("brd").asText("").trim();
        List<String> modules = modules(body);
        List<String> warnings = new ArrayList<>();
        for (JsonNode w : body.path("warnings")) warnings.add(w.asText());

        List<JsonNode> results = new ArrayList<>();
        JsonNode jobPlan = null;
        String key = body.path("job_key").asText("");
        if (!key.isEmpty()) {
            if (!JobStore.validKey(key)) return error(400, "Invalid job_key.");
            JsonNode in = MAPPER.readTree(orThrow(JobStore.get(ImplJob.inputKey(key)), "This generation has expired. Generate again."));
            for (int i = 0; i < in.path("jobs").size(); i++) {
                String r = JobStore.get(ImplJob.resultKey(key, i));
                if (r != null) results.add(MAPPER.readTree(r));
                else warnings.add("Part " + in.path("jobs").get(i).path("part").asInt() + " has no result.");
            }
            String p = JobStore.get(ImplJob.planKey(key));
            if (p != null) jobPlan = MAPPER.readTree(p);
        } else {
            for (JsonNode r : body.path("results")) results.add(r);
        }
        int calls = results.size();
        ObjectNode spec = merge(results, warnings);
        Map<String, ObjectNode> seen = new HashMap<>();
        String summary = spec.path("summary").asText("");
        CrmClient crm = Llm.mock() ? null : CrmMetadata.sessionClient(req);
        ImplOrg org = crm == null ? null : ImplOrg.read(crm, modules, false);
        ImplementServlet.markStatus(spec, org);
        ImplSpec.finish(spec);
        spec.put("summary", summary);
        ArrayNode scope = spec.putArray("modules_in_scope");
        modules.forEach(scope::add);

        boolean needsPlan = ImplementServlet.hasBuildItems(spec);
        if (jobPlan != null) {
            // The job already wrote the plan from these same parts.
            List<ObjectNode> items = new ArrayList<>();
            for (JsonNode t : jobPlan.path("items")) {
                ObjectNode n = ImplSpec.normalize("plan", (ObjectNode) t);
                if (n != null) items.add(n);
            }
            ImplSpec.merge(spec, "plan", items, seen);
            for (JsonNode w : jobPlan.path("warnings")) warnings.add(w.asText());
            ImplSpec.finish(spec);
            for (JsonNode t : spec.path("plan")) ((ObjectNode) t).put("status", "todo");
            needsPlan = false;
            if (items.isEmpty() && ImplementServlet.hasBuildItems(spec)) needsPlan = true;
        }
        if (Llm.mock() || !needsPlan) {
            if (needsPlan) ImplSpec.merge(spec, "plan", ImplPlan.derive(spec), seen);
            ImplSpec.finish(spec);
            needsPlan = false;
        }
        ObjectNode out = ImplementServlet.respond(spec, requirements(brd), org, warnings, Llm.label(),
                Llm.mock() ? 0 : calls, split(brd).chunks.size());
        out.put("needs_plan", needsPlan);
        if (!ImplementServlet.hasAnyItems(spec)) {
            out.put("status", 502);
            out.put("error", warnings.isEmpty() ? "No implementation items were produced."
                    : warnings.get(warnings.size() - 1));
        }
        return out;
    }

    private static String orThrow(String v, String msg) {
        if (v == null) throw new IllegalStateException(msg);
        return v;
    }

    private static ObjectNode submit(JsonNode body) throws Exception {
        if (body.path("brd").asText("").trim().isEmpty()) return error(400, "Field 'brd' is required.");
        if (!body.path("jobs").isArray() || body.path("jobs").size() == 0) return error(400, "Field 'jobs' is required.");
        String key = JobStore.newKey();
        ObjectNode in = MAPPER.createObjectNode();
        in.put("brd", body.path("brd").asText(""));
        in.set("modules", body.path("modules"));
        in.set("answers", body.path("answers"));
        in.put("org_text", body.path("org_text").asText(""));
        in.set("jobs", body.path("jobs"));
        try {
            JobStore.put(ImplJob.inputKey(key), MAPPER.writeValueAsString(in));
            ImplJob.status(key, "queued", 0, body.path("jobs").size() + 1, null, "Waiting for a worker");
        } catch (Exception e) {
            return error(503, "Couldn't save the job to Catalyst Cache (" + ImplementServlet.trim(e.getMessage(), 200)
                    + "). Background generation needs Cache enabled on the project.");
        }
        if ("memory".equalsIgnoreCase(System.getenv("LIFTOFF_JOBSTORE"))) {
            // Local tests: no Catalyst job scheduler; run the worker in this process.
            new Thread(() -> ImplJob.run(key, () -> 15 * 60_000L), "impl-worker-local").start();
            ObjectNode out = MAPPER.createObjectNode();
            out.put("job_key", key);
            return out;
        }
        String pool = System.getenv().getOrDefault("IMPL_JOBPOOL", "liftoff_jobs");
        String target = System.getenv().getOrDefault("IMPL_WORKER_FUNCTION", "impl_worker");
        try {
            org.json.simple.JSONObject params = new org.json.simple.JSONObject();
            params.put("job_key", key);
            com.zc.common.ZCProject.initProject();
            com.zc.component.jobscheduling.beans.job.ZCJobMetaDetail meta =
                    com.zc.component.jobscheduling.beans.job.ZCJobBuilder.functionJobBuilder()
                            .setTargetName(target)
                            .setParams(params)
                            .setJobName("blueprint_" + key)
                            .setJobpoolName(pool)
                            .build();
            com.zc.component.jobscheduling.beans.job.ZCJobDetails job =
                    com.zc.component.jobscheduling.ZCJobScheduling.getInstance().job.submitJob(meta);
            ObjectNode out = MAPPER.createObjectNode();
            out.put("job_key", key);
            if (job != null && job.getJobId() != null) out.put("job_id", String.valueOf(job.getJobId()));
            return out;
        } catch (Exception e) {
            return error(503, "Couldn't queue the background job (" + ImplementServlet.trim(e.getMessage(), 200)
                    + "). Check that the job function '" + target + "' is deployed and a Function job pool named '"
                    + pool + "' exists (see agent/ZIA_GENERATOR.md).");
        }
    }

    private static ObjectNode status(JsonNode body) throws Exception {
        String key = body.path("job_key").asText("");
        if (!JobStore.validKey(key)) return error(400, "Invalid job_key.");
        String s = JobStore.get(ImplJob.statusKey(key));
        if (s == null) return error(404, "No such generation, or it has expired.");
        return (ObjectNode) MAPPER.readTree(s);
    }

    static ObjectNode plan(JsonNode body) {
        JsonNode in = body.path("spec");
        if (!in.isObject()) return error(400, "Field 'spec' is required.");
        ObjectNode spec = (ObjectNode) in.deepCopy();
        List<String> warnings = new ArrayList<>();
        StringBuilder reqs = new StringBuilder();
        for (JsonNode r : body.path("requirements")) {
            String line = "- " + r.path("id").asText() + (r.path("title").asText("").isEmpty() ? "" : " — " + r.path("title").asText()) + "\n";
            if (reqs.length() + line.length() > 1500) break;
            reqs.append(line);
        }
        Map<String, ObjectNode> seen = new HashMap<>();
        spec.putArray("plan");
        boolean planned = false;
        try {
            LlmResult r = Llm.chat(ImplPrompts.planSystem(),
                    ImplPrompts.planPrompt(ImplementServlet.digest(spec, 6000), reqs.toString().trim()), 0);
            ImplSpec.Parsed p = ImplSpec.parse(r.content, List.of("plan"));
            if (p.ok() && !p.items.get("plan").isEmpty()) {
                ImplSpec.merge(spec, "plan", p.items.get("plan"), seen);
                planned = true;
                if (p.salvaged || r.truncated()) warnings.add("Plan response was cut off; kept " + p.size() + " task(s).");
            } else {
                warnings.add("Plan generation failed (" + (p.ok() ? "no tasks" : p.error) + "); derived a plan from the spec instead.");
            }
        } catch (Exception e) {
            warnings.add("Plan generation failed (" + ImplementServlet.trim(e.getMessage(), 160) + "); derived a plan from the spec instead.");
        }
        if (!planned) ImplSpec.merge(spec, "plan", ImplPlan.derive(spec), seen);
        for (JsonNode t : spec.path("plan")) ((ObjectNode) t).remove("id");   // re-numbered by finish()
        ImplSpec.finish(spec);
        for (JsonNode t : spec.path("plan")) ((ObjectNode) t).put("status", "todo");
        ObjectNode out = MAPPER.createObjectNode();
        out.set("spec", spec);
        ArrayNode w = out.putArray("warnings");
        warnings.forEach(w::add);
        return out;
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        CorsSupport.applyOptions(req, resp);
    }
}
