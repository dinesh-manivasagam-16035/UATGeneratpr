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

/**
 * POST /generate/stage — UAT case generation of /generate, split into requests that each fit
 * Catalyst's ~30 s limit for Advanced I/O functions (a whole run makes several LLM calls and is
 * cut off with EXECUTION_TIME_EXCEEDED / HTTP 408). The browser drives the stages:
 *
 *   {"stage":"start",  brd, modules, platform?, creator_owner?, creator_app?}
 *       → {calls:[{module, part, label, focus, wanted}], schemas:{module:text},
 *          requirements:{part:text}, schema_source, warnings, provider}           no LLM call
 *   {"stage":"part",   brd, module, part, focus, wanted, schema, requirements, platform?,
 *                      creator_owner?, creator_app?, retry?}
 *       → {module, part, focus, cases:[...], notes:[...], error?}                  one LLM call
 *   {"stage":"finish", brd, modules, results:[{module, part, focus, cases, notes, error?}],
 *                      schema_source, warnings, project_key?}
 *       → the /generate response (cases, counts, requirements, coverage, projects_payload)
 *                                                                                   no LLM call
 *
 * Background mode (an agent reply can take longer than one request allows), preferred by the
 * browser; the per-request "part" stage is the fallback when background jobs aren't set up:
 *   {"stage":"submit", brd, modules, platform?, creator_owner?, creator_app?, calls, schemas,
 *                      requirements}
 *       → {job_key, job_id?}   queues a "uat_generate" job on impl_worker (UatJob), one step per call
 *   {"stage":"status", job_key}  → {state: queued|running|done|failed, done, total, label?, error?, steps}
 *   {"stage":"finish", brd, modules, job_key, schema_source, warnings, project_key?}
 *       → as above, reading the parts' results from the job
 *
 * Every stage is stateless: the BRD is re-chunked the same way each time (same env), and the
 * browser carries the schema text and part results between calls. A "part" never retries
 * in-request; the browser sends it again with retry:true, which asks for fewer cases.
 *
 * Tunables: as /generate (GEN_CHUNK_CHARS, GEN_MAX_CHUNKS, MAX_LLM_CALLS, CASES_PER_CALL,
 * TARGET_CASES_PER_MODULE), plus GEN_CALL_DEADLINE_SEC (26) for one part.
 */
public class GenerateStageServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(GenerateStageServlet.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true);

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        CorsSupport.apply(req, resp);
        try {
            if (!Llm.mock() && !Llm.available()) {
                GenerateServlet.writeError(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, Llm.notConfiguredMessage());
                return;
            }
            JsonNode body = MAPPER.readTree(req.getInputStream());
            String stage = body.path("stage").asText("");
            ObjectNode out;
            switch (stage) {
                case "start":  out = start(req, body); break;
                case "part":   out = part(body); break;
                case "finish": out = finish(req, body); break;
                case "submit": out = submit(body); break;
                case "status": out = JobQueue.status(body.path("job_key").asText("")); break;
                default:
                    GenerateServlet.writeError(resp, 400, "Field 'stage' must be start, part, submit, status or finish.");
                    return;
            }
            // A part's or a job's own failure is data for the caller, not a failed request.
            boolean reported = "part".equals(stage) || ("status".equals(stage) && out.has("state"));
            if (out.has("error") && !reported) resp.setStatus(out.path("status").asInt(400));
            else resp.setStatus(200);
            out.remove("status");
            resp.getWriter().write(MAPPER.writeValueAsString(out));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "generate/stage failed", e);
            GenerateServlet.writeError(resp, 500, e.getMessage());
        }
    }

    private static ObjectNode error(int status, String msg) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("status", status);
        o.put("error", msg);
        return o;
    }

    // ---- stages ----

    private static ObjectNode start(HttpServletRequest req, JsonNode body) {
        GenerateServlet.Prep p = GenerateServlet.inputs(body);
        if (p.brd.isEmpty() || p.modules.isEmpty()) {
            return error(400, "Fields 'brd' and 'modules' (array of up to 3) are required.");
        }
        GenerateServlet.prepare(req, p, true);
        int maxCalls = Math.max(1, GenerateServlet.envInt("MAX_LLM_CALLS", 8));
        List<GenerateServlet.Task> tasks = GenerateServlet.plan(p.modules, p.split.chunks, Llm.mock(), p.warnings, maxCalls);

        ObjectNode out = MAPPER.createObjectNode();
        out.put("provider", Llm.label());
        out.put("chunks", p.split.chunks.size());
        ArrayNode calls = out.putArray("calls");
        for (GenerateServlet.Task t : tasks) {
            calls.addObject()
                    .put("module", t.module)
                    .put("part", t.chunk.index + 1)
                    .put("label", t.chunk.label())
                    .put("focus", t.focus)
                    .put("wanted", t.wanted);
        }
        ObjectNode schemas = out.putObject("schemas");
        p.schemas.forEach(schemas::put);
        ObjectNode reqs = out.putObject("requirements");
        p.reqTexts.forEach((idx, text) -> reqs.put(String.valueOf(idx + 1), text));
        ObjectNode src = out.putObject("schema_source");
        p.schemaSource.forEach(src::put);
        ArrayNode w = out.putArray("warnings");
        p.warnings.forEach(w::add);
        return out;
    }

    private static ObjectNode part(JsonNode body) {
        GenerateServlet.Prep p = GenerateServlet.inputs(body);
        String module = body.path("module").asText("").trim();
        int partNo = body.path("part").asInt(1);
        String focus = body.path("focus").asText("").trim();
        ObjectNode out = MAPPER.createObjectNode();
        out.put("module", module);
        out.put("part", partNo);
        out.put("focus", focus);
        if (p.brd.isEmpty() || module.isEmpty()) {
            out.put("error", "Fields 'brd' and 'module' are required.");
            return out;
        }
        BrdChunker.Result split = GenerateServlet.split(p.brd, Llm.mock());
        int idx = partNo - 1;
        if (idx < 0 || idx >= split.chunks.size()) {
            out.put("error", "No part " + partNo + " in this BRD.");
            return out;
        }
        int wanted = Math.max(3, Math.min(20, body.path("wanted").asInt(8)));
        GenerateServlet.Task t = new GenerateServlet.Task(0, module, split.chunks.get(idx),
                focus.isEmpty() ? "all scenario types" : focus, wanted);
        String schema = body.has("schema") ? body.path("schema").asText("") : ModuleSchemas.forModule(module);
        String reqText = body.has("requirements") ? body.path("requirements").asText("")
                : Llm.mock() ? "" : Requirements.promptList(GenerateServlet.requirements(p.brd), partNo, 1500);
        String retryNote = body.path("retry").asBoolean(false)
                ? "Your previous answer for this part could not be used or was cut off." : null;
        long deadline = System.currentTimeMillis() + GenerateServlet.envInt("GEN_CALL_DEADLINE_SEC", 26) * 1000L;

        GenerateServlet.Outcome o = GenerateServlet.runTask(t, split.chunks.size(), split.outline, p.platform,
                p.creatorOwner, p.creatorApp, p.brd, Llm.provider(), deadline, schema, reqText, retryNote);
        if (o.error != null) out.put("error", GenerateServlet.trim(o.error, 300));
        ArrayNode cases = out.putArray("cases");
        o.cases.forEach(cases::add);
        ArrayNode notes = out.putArray("notes");
        o.notes.forEach(notes::add);
        return out;
    }

    private static ObjectNode submit(JsonNode body) {
        GenerateServlet.Prep p = GenerateServlet.inputs(body);
        JsonNode calls = body.path("calls");
        if (p.brd.isEmpty() || !calls.isArray() || calls.size() == 0) {
            return error(400, "Fields 'brd' and 'calls' (from the start stage) are required.");
        }
        int maxCalls = Math.max(1, GenerateServlet.envInt("MAX_LLM_CALLS", 8));
        if (!Llm.mock() && calls.size() > maxCalls) {
            return error(400, "At most " + maxCalls + " calls per run (MAX_LLM_CALLS); got " + calls.size() + ".");
        }
        ObjectNode in = MAPPER.createObjectNode();
        in.put("kind", "uat_generate");
        in.put("brd", p.brd);
        in.put("platform", p.platform);
        in.put("creator_owner", p.creatorOwner);
        in.put("creator_app", p.creatorApp);
        ArrayNode cs = in.putArray("calls");
        for (JsonNode c : calls) {
            cs.addObject().put("module", c.path("module").asText(""))
                    .put("part", c.path("part").asInt(1))
                    .put("label", c.path("label").asText(""))
                    .put("focus", c.path("focus").asText(""))
                    .put("wanted", c.path("wanted").asInt(8));
        }
        in.set("schemas", body.path("schemas").isObject() ? body.path("schemas") : MAPPER.createObjectNode());
        in.set("requirements", body.path("requirements").isObject() ? body.path("requirements") : MAPPER.createObjectNode());
        return JobQueue.submit(JobStore.newKey(), "uat_generate", in, calls.size());
    }

    /** The parts' results of a finished generation job, in call order; a missing one becomes an error entry. */
    private static List<JsonNode> jobResults(String key) throws Exception {
        String raw = JobStore.get(ImplJob.inputKey(key));
        if (raw == null) throw new IllegalStateException("This generation has expired. Generate again.");
        JsonNode in = MAPPER.readTree(raw);
        List<JsonNode> out = new ArrayList<>();
        int n = 0;
        for (JsonNode c : in.path("calls")) {
            String r = JobStore.get(ImplJob.resultKey(key, n++));
            if (r != null) { out.add(MAPPER.readTree(r)); continue; }
            ObjectNode miss = MAPPER.createObjectNode();
            miss.put("module", c.path("module").asText(""));
            miss.put("part", c.path("part").asInt(1));
            miss.put("focus", c.path("focus").asText(""));
            miss.put("error", "the background job didn't finish this part");
            out.add(miss);
        }
        return out;
    }

    private static ObjectNode finish(HttpServletRequest req, JsonNode body) throws Exception {
        GenerateServlet.Prep p = GenerateServlet.inputs(body);
        if (p.brd.isEmpty() || p.modules.isEmpty()) {
            return error(400, "Fields 'brd' and 'modules' are required.");
        }
        GenerateServlet.prepare(req, p, false);
        p.warnings.clear();                                   // start's warnings come back from the browser
        for (JsonNode w : body.path("warnings")) p.warnings.add(w.asText());
        body.path("schema_source").fields().forEachRemaining(e -> p.schemaSource.put(e.getKey(), e.getValue().asText()));

        BrdChunker.Result split = p.split;
        List<GenerateServlet.Done> done = new ArrayList<>();
        int calls = 0;
        String key = body.path("job_key").asText("");
        if (!key.isEmpty() && !JobStore.validKey(key)) return error(400, "Invalid job_key.");
        Iterable<JsonNode> results = key.isEmpty() ? body.path("results") : jobResults(key);
        for (JsonNode r : results) {
            calls++;
            String module = r.path("module").asText("");
            int part = r.path("part").asInt(1);
            String label = part >= 1 && part <= split.chunks.size() ? split.chunks.get(part - 1).label() : "Part " + part;
            GenerateServlet.Outcome o = new GenerateServlet.Outcome();
            String err = r.path("error").asText("");
            if (!err.isEmpty()) o.error = err;
            for (JsonNode n : r.path("notes")) o.notes.add(n.asText());
            int dropped = 0;
            for (JsonNode c : r.path("cases")) {
                // Cases came back from the browser or the cache: normalise them again rather than trust them.
                ObjectNode norm = CaseParser.normalize(c);
                if (norm != null) o.cases.add(norm); else dropped++;
            }
            if (dropped > 0) o.notes.add(dropped + " malformed case(s) dropped");
            done.add(new GenerateServlet.Done(module, module + " / " + label, r.path("focus").asText(""), o));
        }

        ObjectNode out = GenerateServlet.assemble(req, p, done, calls, Llm.label(), Llm.mock());
        if (out.has("error")) out.put("status", 502);
        return out;
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        CorsSupport.applyOptions(req, resp);
    }
}
