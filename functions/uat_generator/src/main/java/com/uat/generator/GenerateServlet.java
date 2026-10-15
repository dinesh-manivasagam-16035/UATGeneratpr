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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * POST /generate
 *
 * Body: {
 *   "brd":     "Full BRD text...",
 *   "modules": ["Leads", "Deals"],   // 1..3 module api_names
 *   "module":  "Leads",              // legacy single-module form (still accepted)
 *   "platform": "crm" | "creator",
 *   "creator_owner": "...", "creator_app": "...",   // creator only
 *   "project_key": "ACME-UAT"        // optional, embedded into projects_payload
 * }
 *
 * How generation works:
 *   1. The BRD is split into section-aligned chunks (BrdChunker), so nothing is
 *      silently truncated.
 *   2. Each (module, chunk) pair gets 1–3 LLM calls, each with a different focus
 *      (success paths / validations / permissions and edge cases) and a small
 *      case budget, so responses fit the provider's output limit.
 *   3. Calls run in parallel up to GEN_CONCURRENCY, inside an overall deadline.
 *   4. Each response is parsed and validated (CaseParser). A cut-off response
 *      keeps its complete cases; an unusable one is retried once with fewer cases.
 *   5. Cases are de-duplicated per module by title.
 *   6. Grounding: when CRM is connected, each module's real field metadata
 *      (CrmMetadata) replaces the built-in template in the prompt.
 *   7. Traceability: requirements are extracted from the BRD (Requirements),
 *      each case is linked to requirement IDs, and coverage is reported.
 * Anything skipped or failed is listed in "warnings" instead of being dropped silently.
 *
 * Tunables (env vars): GEN_CHUNK_CHARS (9000), GEN_MAX_CHUNKS (6), MAX_LLM_CALLS (8),
 * CASES_PER_CALL (8), TARGET_CASES_PER_MODULE (30), GEN_CONCURRENCY (2),
 * GEN_DEADLINE_SEC (140), LLM_MAX_TOKENS (4000, in ClaudeClient).
 */
public class GenerateServlet extends HttpServlet {

    private static final Logger LOG = Logger.getLogger(GenerateServlet.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true);
    private static final int MAX_MODULES = 3;
    private static final long MIN_TIME_FOR_RETRY_MS = 45_000;

    private static final String FOCUS_ALL =
            "all scenario types for this part — use cases, workflows, validations, permissions "
          + "and edge cases — most important (P0) first";
    private static final String[][] FOCUS_BY_PASSES = {
        { FOCUS_ALL },
        { "success paths, use cases, workflows, automations and stage transitions",
          "validation rules, required fields, negative cases, permissions and boundary conditions" },
        { "success paths, use cases, workflows, automations and stage transitions",
          "validation rules, required fields, formats and negative cases",
          "roles and permissions, cross-field rules, boundary conditions and edge cases" },
    };

    /** One planned LLM call. */
    private static final class Task {
        final int order;
        final String module;
        final BrdChunker.Chunk chunk;
        final String focus;
        final int wanted;
        Task(int order, String module, BrdChunker.Chunk chunk, String focus, int wanted) {
            this.order = order; this.module = module; this.chunk = chunk;
            this.focus = focus; this.wanted = wanted;
        }
    }

    private static final class Outcome {
        final List<ObjectNode> cases = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        String error;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        CorsSupport.apply(req, resp);

        String llmProvider = System.getenv().getOrDefault("LLM_PROVIDER", "github");
        String model = System.getenv().getOrDefault("COPILOT_MODEL", "gpt-4o");
        boolean mock = "mock".equalsIgnoreCase(llmProvider);
        boolean zia = "zia".equalsIgnoreCase(llmProvider);
        String provider = mock ? "mock" : zia ? "zia" : "GitHub Models (" + model + ")";
        int brdLength = 0;
        List<String> modules = new ArrayList<>();
        ExecutorService pool = null;

        try {
            JsonNode body = MAPPER.readTree(req.getInputStream());
            String brd = textOf(body, "brd");
            String projectKey = textOf(body, "project_key");
            String platform = textOf(body, "platform");
            if (platform.isEmpty()) platform = "crm";
            String creatorOwner = textOf(body, "creator_owner");
            String creatorApp = textOf(body, "creator_app");
            brdLength = brd.length();

            JsonNode mArr = body.get("modules");
            if (mArr != null && mArr.isArray()) {
                for (JsonNode m : mArr) {
                    String v = m.asText("").trim();
                    if (!v.isEmpty() && !modules.contains(v)) modules.add(v);
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

            List<String> warnings = new ArrayList<>();
            if (modules.size() > MAX_MODULES) {
                warnings.add("Only the first " + MAX_MODULES + " modules were used: "
                        + String.join(", ", modules.subList(MAX_MODULES, modules.size()))
                        + " skipped.");
                modules = new ArrayList<>(modules.subList(0, MAX_MODULES));
            }

            // ---- 1. Chunk the BRD (mock mode uses the whole BRD in one go) ----
            BrdChunker.Result split = mock
                    ? BrdChunker.chunk(brd, Integer.MAX_VALUE / 2, 1)
                    : BrdChunker.chunk(brd, envInt("GEN_CHUNK_CHARS", 9000), envInt("GEN_MAX_CHUNKS", 6));
            List<BrdChunker.Chunk> chunks = split.chunks;
            if (split.truncated()) {
                warnings.add("The BRD is longer than this run can cover. Sections not used: "
                        + summarize(split.droppedHeadings)
                        + ". Generate those sections separately, or raise GEN_MAX_CHUNKS.");
            }

            // ---- 1b. Requirements (from the full BRD, including parts this run can't cover) ----
            int chunkChars = envInt("GEN_CHUNK_CHARS", 9000);
            List<BrdChunker.Chunk> allChunks = BrdChunker.chunk(brd, chunkChars, 10_000).chunks;
            List<Requirements.Requirement> reqs = Requirements.extract(allChunks);
            Map<Integer, String> reqTexts = new LinkedHashMap<>();
            if (!mock) {
                for (BrdChunker.Chunk c : chunks) {
                    reqTexts.put(c.index, Requirements.promptList(reqs, c.index + 1, 1500));
                }
            }

            // ---- 1c. Field schema per module: live from CRM when connected ----
            Map<String, String> schemas = new LinkedHashMap<>();
            Map<String, String> schemaSource = new LinkedHashMap<>();
            boolean crmPlatform = !"creator".equalsIgnoreCase(platform);
            CrmClient crm = (crmPlatform && !mock) ? CrmMetadata.sessionClient(req) : null;
            for (String m : modules) {
                if (!crmPlatform) { schemas.put(m, ""); continue; }
                if (crm != null) {
                    try {
                        schemas.put(m, CrmMetadata.describe(crm, m));
                        schemaSource.put(m, "live");
                        continue;
                    } catch (Exception e) {
                        warnings.add("Couldn't read " + m + " fields from CRM (" + trim(e.getMessage(), 160)
                                + "); used the built-in template instead.");
                    }
                }
                schemas.put(m, ModuleSchemas.forModule(m));
                schemaSource.put(m, ModuleSchemas.has(m) ? "builtin" : "generic");
            }
            if (crmPlatform && !mock && crm == null) {
                warnings.add("Not connected to Zoho CRM, so cases use generic field templates. "
                        + "Connect CRM before generating to ground cases in your org's real fields.");
            }

            // ---- 2. Plan the calls ----
            List<Task> tasks = plan(modules, chunks, mock || zia, warnings);

            // ---- 3. Run them in parallel inside a deadline ----
            final String fPlatform = platform;
            final String outline = split.outline;
            long deadline = System.currentTimeMillis() + envInt("GEN_DEADLINE_SEC", 140) * 1000L;
            int concurrency = Math.max(1, Math.min(envInt("GEN_CONCURRENCY", 2), tasks.size()));
            pool = Executors.newFixedThreadPool(concurrency);

            List<Future<Outcome>> futures = new ArrayList<>();
            for (Task t : tasks) {
                futures.add(pool.submit(() -> runTask(t, chunks.size(), outline, fPlatform,
                        creatorOwner, creatorApp, brd, llmProvider, deadline,
                        schemas.getOrDefault(t.module, ""), reqTexts.getOrDefault(t.chunk.index, ""))));
            }

            Outcome[] outcomes = new Outcome[tasks.size()];
            for (int i = 0; i < futures.size(); i++) {
                Task t = tasks.get(i);
                long remaining = deadline - System.currentTimeMillis();
                try {
                    outcomes[i] = futures.get(i).get(Math.max(1, remaining), TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    futures.get(i).cancel(true);
                    Outcome o = new Outcome();
                    o.error = "ran out of time";
                    outcomes[i] = o;
                } catch (Exception e) {
                    Outcome o = new Outcome();
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    o.error = cause.getMessage();
                    outcomes[i] = o;
                }
            }

            // ---- 4. Merge, de-duplicate, report ----
            ArrayNode allCases = MAPPER.createArrayNode();
            Map<String, Integer> perModuleCount = new LinkedHashMap<>();
            Map<String, List<String>> perModuleErrors = new LinkedHashMap<>();
            for (String m : modules) {
                perModuleCount.put(m, 0);
                perModuleErrors.put(m, new ArrayList<>());
            }
            Set<String> seen = new HashSet<>();
            int duplicates = 0;
            String firstError = null;

            for (int i = 0; i < tasks.size(); i++) {
                Task t = tasks.get(i);
                Outcome o = outcomes[i];
                String where = t.module + " / " + t.chunk.label();
                for (String note : o.notes) warnings.add(where + ": " + note);
                if (o.error != null) {
                    warnings.add(where + " (" + shortFocus(t.focus) + ") failed: " + o.error);
                    perModuleErrors.get(t.module).add(o.error);
                    if (firstError == null) firstError = o.error;
                }
                for (ObjectNode c : o.cases) {
                    String key = t.module + "|" + c.path("title").asText("")
                            .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
                    if (!seen.add(key)) { duplicates++; continue; }
                    c.put("module", t.module);
                    allCases.add(c);
                    perModuleCount.merge(t.module, 1, Integer::sum);
                }
            }
            if (duplicates > 0) warnings.add(duplicates + " duplicate case(s) removed.");

            // ---- 5. Traceability: link cases to requirements, compute coverage ----
            Set<String> knownIds = new HashSet<>();
            for (Requirements.Requirement r : reqs) knownIds.add(r.id);
            Map<String, Integer> casesPerReq = new LinkedHashMap<>();
            for (Requirements.Requirement r : reqs) casesPerReq.put(r.id, 0);
            int untraced = 0;
            for (JsonNode n : allCases) {
                ObjectNode c = (ObjectNode) n;
                List<String> ids = new ArrayList<>();
                for (JsonNode id : c.path("req_ids")) {
                    if (knownIds.contains(id.asText()) && !ids.contains(id.asText())) ids.add(id.asText());
                }
                if (ids.isEmpty()) ids = Requirements.infer(c.path("spec_ref").asText(""), reqs);
                ArrayNode idArr = c.putArray("req_ids");
                for (String id : ids) {
                    idArr.add(id);
                    casesPerReq.merge(id, 1, Integer::sum);
                }
                if (ids.isEmpty() && !reqs.isEmpty()) untraced++;
            }
            List<String> uncovered = new ArrayList<>();
            List<String> notReached = new ArrayList<>();
            ArrayNode reqOut = MAPPER.createArrayNode();
            for (Requirements.Requirement r : reqs) {
                int n = casesPerReq.get(r.id);
                boolean inRun = mock || r.part <= chunks.size();
                ObjectNode o = reqOut.addObject();
                o.put("id", r.id);
                o.put("title", r.title);
                o.put("part", r.part);
                o.put("derived", r.derived);
                o.put("cases", n);
                o.put("in_run", inRun);
                if (n == 0) { if (inRun) uncovered.add(r.id); else notReached.add(r.id); }
            }
            if (!mock && !uncovered.isEmpty()) {
                warnings.add("No cases for " + uncovered.size() + " requirement(s): " + summarize(uncovered)
                        + ". Regenerate, or check that the BRD describes testable behaviour for them.");
            }
            if (untraced > 0) {
                warnings.add(untraced + " case(s) couldn't be linked to a requirement.");
            }

            for (String m : modules) {
                int n = perModuleCount.get(m);
                List<String> errs = perModuleErrors.get(m);
                String status = n == 0 ? "failed" : errs.isEmpty() ? "generated" : "partial";
                RunLogger.logGenerate(req, m, provider, brdLength, n, status,
                        errs.isEmpty() ? null : String.join(" | ", errs));
            }

            if (allCases.size() == 0 && firstError != null) {
                writeError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, firstError);
                return;
            }

            ObjectNode out = MAPPER.createObjectNode();
            ArrayNode modulesOut = out.putArray("modules");
            modules.forEach(modulesOut::add);
            out.put("provider", provider);
            ObjectNode counts = out.putObject("counts");
            perModuleCount.forEach(counts::put);
            out.put("total", allCases.size());
            out.put("chunks", chunks.size());
            out.put("llm_calls", tasks.size());
            ObjectNode src = out.putObject("schema_source");
            schemaSource.forEach(src::put);
            out.set("requirements", reqOut);
            ObjectNode cov = out.putObject("coverage");
            cov.put("requirements", reqs.size());
            cov.put("covered", reqs.size() - uncovered.size() - notReached.size());
            ArrayNode unc = cov.putArray("uncovered");
            uncovered.forEach(unc::add);
            ArrayNode nr = cov.putArray("not_in_run");
            notReached.forEach(nr::add);
            cov.put("untraced_cases", untraced);
            ArrayNode warnOut = out.putArray("warnings");
            warnings.forEach(warnOut::add);
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
        } finally {
            if (pool != null) pool.shutdownNow();
        }
    }

    /**
     * Decides how many calls each (module, chunk) pair gets. Every pair gets at
     * least one call when the budget allows; spare budget becomes extra focused
     * passes. Pairs that don't fit the budget are reported, chunk by chunk, so
     * every module covers the start of the BRD first.
     */
    private static List<Task> plan(List<String> modules, List<BrdChunker.Chunk> chunks,
                                   boolean singlePass, List<String> warnings) {
        int maxCalls = Math.max(1, envInt("MAX_LLM_CALLS", 8));
        int perCall = Math.max(3, envInt("CASES_PER_CALL", 8));
        int target = Math.max(perCall, envInt("TARGET_CASES_PER_MODULE", 30));
        int units = modules.size() * chunks.size();

        int passes = 1;
        if (!singlePass && units < maxCalls) {
            int wantedPerUnit = (int) Math.ceil((double) target / chunks.size());
            int needed = (int) Math.ceil((double) wantedPerUnit / perCall);
            passes = Math.max(1, Math.min(3, Math.min(maxCalls / units, needed)));
        }
        int wanted = passes == 1 ? perCall
                : Math.max(4, Math.min(perCall,
                    (int) Math.ceil((double) target / chunks.size() / passes)));
        String[] focuses = FOCUS_BY_PASSES[passes - 1];

        List<Task> tasks = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int order = 0;
        for (BrdChunker.Chunk c : chunks) {
            for (String m : modules) {
                for (String f : focuses) {
                    if (tasks.size() < maxCalls) tasks.add(new Task(order++, m, c, f, wanted));
                    else if (!skipped.contains(m + " / " + c.label())) skipped.add(m + " / " + c.label());
                }
            }
        }
        if (!skipped.isEmpty()) {
            warnings.add("Call budget (MAX_LLM_CALLS=" + maxCalls + ") reached; not generated for: "
                    + summarize(skipped) + ". Pick fewer modules or raise MAX_LLM_CALLS.");
        }
        return tasks;
    }

    private static Outcome runTask(Task t, int totalParts, String outline, String platform,
                                   String creatorOwner, String creatorApp, String fullBrd,
                                   String llmProvider, long deadline,
                                   String moduleSchema, String requirements) {
        Outcome o = new Outcome();
        GenerationRequest r = new GenerationRequest();
        r.platform = platform;
        r.module = t.module;
        r.moduleSchema = moduleSchema;
        r.requirements = requirements;
        r.creatorOwner = creatorOwner;
        r.creatorApp = creatorApp;
        r.outline = outline;
        r.chunkText = t.chunk.text;
        r.part = t.chunk.index + 1;
        r.totalParts = totalParts;
        r.focus = t.focus;
        r.wantedCases = t.wanted;

        try {
            LlmResult res = call(r, llmProvider, fullBrd);
            CaseParser.Parsed p = CaseParser.parse(res.content);
            if (p.ok() && !p.cases.isEmpty()) {
                o.cases.addAll(p.cases);
                if (res.truncated() || p.salvaged) {
                    o.notes.add("response was cut off; kept " + p.cases.size() + " complete case(s)");
                }
                if (p.dropped > 0) o.notes.add(p.dropped + " malformed case(s) dropped");
                return o;
            }

            if (deadline - System.currentTimeMillis() < MIN_TIME_FOR_RETRY_MS) {
                o.error = p.ok() ? "no usable cases returned" : p.error;
                return o;
            }

            // One retry with a smaller budget and an explanation.
            r.wantedCases = Math.max(3, t.wanted / 2);
            r.retryNote = res.truncated()
                    ? "Your previous answer was cut off by the output limit. Return at most "
                      + r.wantedCases + " cases with short descriptions and compact execution plans."
                    : "Your previous answer could not be used (" + trim(p.ok() ? "no valid cases" : p.error, 200)
                      + "). Return valid JSON exactly in the requested shape, at most " + r.wantedCases + " cases.";
            LlmResult res2 = call(r, llmProvider, fullBrd);
            CaseParser.Parsed p2 = CaseParser.parse(res2.content);
            if (p2.ok() && !p2.cases.isEmpty()) {
                o.cases.addAll(p2.cases);
                o.notes.add("needed a retry; kept " + p2.cases.size() + " case(s)");
                return o;
            }
            o.error = p2.ok() ? "no usable cases returned" : p2.error;
        } catch (Exception e) {
            o.error = e.getMessage();
        }
        return o;
    }

    private static LlmResult call(GenerationRequest r, String llmProvider, String fullBrd) throws Exception {
        if ("mock".equalsIgnoreCase(llmProvider)) {
            return new LlmResult(MockLlmClient.generate(fullBrd, r.module, r.moduleSchema), "stop");
        }
        if ("zia".equalsIgnoreCase(llmProvider)) {
            return new LlmResult(ZiaClient.generate(r.chunkText, r.module, r.moduleSchema), null);
        }
        return ClaudeClient.generateChunk(r);
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        CorsSupport.applyOptions(req, resp);
    }

    private static int envInt(String name, int def) {
        String v = System.getenv(name);
        if (v == null || v.trim().isEmpty()) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static String summarize(List<String> items) {
        if (items.size() <= 5) return String.join(", ", items);
        return String.join(", ", items.subList(0, 5)) + " and " + (items.size() - 5) + " more";
    }

    private static String shortFocus(String focus) {
        int comma = focus.indexOf(',');
        return comma > 0 ? focus.substring(0, comma) : focus;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String textOf(JsonNode body, String field) {
        JsonNode n = body.get(field);
        return n == null || n.isNull() ? "" : n.asText("").trim();
    }

    private static void writeError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setStatus(status);
        ObjectNode err = MAPPER.createObjectNode();
        err.put("error", message == null ? "unknown" : message);
        resp.getWriter().write(MAPPER.writeValueAsString(err));
    }
}
