package com.uat.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * UAT case generation and execution as background jobs on the impl_worker job function (up to
 * 15 min), dispatched from ImplJob.run by the input's "kind". Same calls as /generate/stage
 * "part" and /execute; inputs, progress and results go through JobStore. Status has the same
 * shape as a blueprint job: {state, done, total, label, steps:[{label, detail, state, seconds,
 * items?, result?}]}.
 *
 * uat_generate, job key K:
 *   K + "i"       {kind, brd, platform, creator_owner, creator_app, calls:[{module, part, label,
 *                  focus, wanted}], schemas:{module: text}, requirements:{part: text}}
 *   K + "r" + n   result of calls[n]: {module, part, focus, cases, notes, error?}
 * uat_execute:
 *   K + "i"       {kind, cases, platform, dry_run, api_base?, access_token?}; the access token is
 *                 removed from the input when the job ends
 *   K + "r" + c   executed cases c*EXEC_CHUNK … (c+1)*EXEC_CHUNK-1, written as each chunk finishes
 */
final class UatJob {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final int EXEC_CHUNK = 10;

    private UatJob() {}

    // ---- generation ----

    static boolean generate(String key, JsonNode in, LongSupplier remainingMs) throws Exception {
        ArrayNode calls = (ArrayNode) in.path("calls");
        int total = calls.size();
        List<String[]> rows = new ArrayList<>();
        for (JsonNode c : calls) {
            rows.add(new String[]{c.path("module").asText() + " · part " + c.path("part").asInt() + " · " + shortFocus(c.path("focus").asText()),
                    c.path("label").asText() + " — " + c.path("focus").asText()});
        }
        ImplJob.Steps steps = new ImplJob.Steps(rows);
        ImplJob.status(key, "running", 0, total, null, "Starting", steps);
        // Calls here aren't bound by Catalyst's 30 s request limit.
        ZiaAgentClient.timeoutSecOverride = ImplementServlet.envInt("IMPL_WORKER_CALL_TIMEOUT_SEC", 240);
        ZiaAgentClient.retrySecOverride = new int[]{8, 20};   // transient 5xx/429: two more tries

        String brd = in.path("brd").asText("");
        BrdChunker.Result split = GenerateServlet.split(brd, Llm.mock());
        int[] done = {0};
        int threads = Math.max(1, Math.min(GenerateServlet.envInt("GEN_CONCURRENCY", 2), total));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                final int n = i;
                futures.add(pool.submit(() -> {
                    JsonNode c = calls.get(n);
                    steps.start(n);
                    synchronized (done) { ImplJob.status(key, "running", done[0], total, null, steps.label(n), steps); }
                    ObjectNode r = part(in, c, split, remainingMs);
                    JobStore.put(ImplJob.resultKey(key, n), MAPPER.writeValueAsString(r));
                    steps.finish(n, r.has("error"), r.path("cases").size());
                    if (r.has("error")) steps.set(n, "error", ImplementServlet.trim(r.path("error").asText(""), 300));
                    synchronized (done) {
                        done[0]++;
                        ImplJob.status(key, "running", done[0], total, null, steps.label(n), steps);
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) f.get(Math.max(1000, remainingMs.getAsLong() - 20_000), TimeUnit.MILLISECONDS);
        } finally {
            pool.shutdownNow();
        }
        ImplJob.status(key, "done", total, total, null, "Done", steps);
        return true;
    }

    /** One planned call, as the /generate/stage "part" response; the call may retry once, time allowing. */
    private static ObjectNode part(JsonNode in, JsonNode c, BrdChunker.Result split, LongSupplier remainingMs) {
        String module = c.path("module").asText("");
        int partNo = c.path("part").asInt(1);
        ObjectNode out = MAPPER.createObjectNode();
        out.put("module", module);
        out.put("part", partNo);
        out.put("focus", c.path("focus").asText(""));
        ArrayNode cases = out.putArray("cases");
        ArrayNode notes = out.putArray("notes");
        int idx = partNo - 1;
        if (idx < 0 || idx >= split.chunks.size()) {
            out.put("error", "No part " + partNo + " in this BRD.");
            return out;
        }
        GenerateServlet.Task t = new GenerateServlet.Task(0, module, split.chunks.get(idx),
                c.path("focus").asText("all scenario types"), Math.max(3, Math.min(20, c.path("wanted").asInt(8))));
        // Leave the job time to save its results; a call that needs more is reported, not lost silently.
        long deadline = System.currentTimeMillis() + Math.max(30_000, remainingMs.getAsLong() - 45_000);
        GenerateServlet.Outcome o = GenerateServlet.runTask(t, split.chunks.size(), split.outline,
                in.path("platform").asText("crm"), in.path("creator_owner").asText(""), in.path("creator_app").asText(""),
                in.path("brd").asText(""), Llm.provider(), deadline,
                in.path("schemas").path(module).asText(""), in.path("requirements").path(String.valueOf(partNo)).asText(""), null);
        if (o.error != null) out.put("error", GenerateServlet.trim(o.error, 300));
        o.cases.forEach(cases::add);
        o.notes.forEach(notes::add);
        return out;
    }

    // ---- execution ----

    static boolean execute(String key, JsonNode in, LongSupplier remainingMs) throws Exception {
        ArrayNode cases = (ArrayNode) in.path("cases");
        int total = cases.size();
        List<String[]> rows = new ArrayList<>();
        for (JsonNode tc : cases) {
            rows.add(new String[]{tc.path("title").asText("Untitled case"),
                    tc.path("module").asText("") + (tc.path("priority").asText("").isEmpty() ? "" : " · " + tc.path("priority").asText())});
        }
        ImplJob.Steps steps = new ImplJob.Steps(rows);
        ImplJob.status(key, "running", 0, total, null, "Starting", steps);

        boolean dryRun = in.path("dry_run").asBoolean(false);
        String token = in.path("access_token").asText("");
        CrmClient client = dryRun || token.isEmpty() ? new CrmClient("", "", "", "", "")
                : CrmClient.withAccessToken(in.path("api_base").asText(""), token);
        try {
            ArrayNode chunk = MAPPER.createArrayNode();
            for (int i = 0; i < total; i++) {
                JsonNode tc = cases.get(i);
                ObjectNode copy = tc.deepCopy();
                ObjectNode result;
                if (remainingMs.getAsLong() < 30_000) {
                    result = MAPPER.createObjectNode();
                    result.put("status", "skipped");
                    result.put("reason", "The background job ran out of time before this case ran.");
                    steps.start(i);
                } else {
                    steps.start(i);
                    ImplJob.status(key, "running", i, total, null, steps.label(i), steps);
                    result = CrmExecutor.execute(tc, client);
                }
                copy.set("execution_result", result);
                chunk.add(copy);
                String status = result.path("status").asText("");
                steps.finish(i, "fail".equals(status), 0);
                steps.set(i, "result", status);
                if (chunk.size() == EXEC_CHUNK || i == total - 1) {
                    JobStore.put(ImplJob.resultKey(key, i / EXEC_CHUNK), MAPPER.writeValueAsString(chunk));
                    chunk = MAPPER.createArrayNode();
                }
                ImplJob.status(key, "running", i + 1, total, null, steps.label(i), steps);
            }
            ImplJob.status(key, "done", total, total, null, "Done", steps);
            return true;
        } finally {
            forgetToken(key, in);
        }
    }

    /** The CRM access token is only needed while the job runs: drop it from the cached input. */
    private static void forgetToken(String key, JsonNode in) {
        if (!in.has("access_token")) return;
        try {
            ObjectNode copy = ((ObjectNode) in).deepCopy();
            copy.remove("access_token");
            JobStore.put(ImplJob.inputKey(key), MAPPER.writeValueAsString(copy));
        } catch (Exception ignored) {
            // The entry still expires with the cache (24 h); the token itself lasts an hour.
        }
    }

    /** All executed cases of a finished (or partly finished) execution job, in order; null entries are missing. */
    static List<JsonNode> executed(String key, int total) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (int c = 0; c * EXEC_CHUNK < total; c++) {
            String raw = JobStore.get(ImplJob.resultKey(key, c));
            JsonNode arr = raw == null ? null : MAPPER.readTree(raw);
            for (int i = 0; i < EXEC_CHUNK && c * EXEC_CHUNK + i < total; i++) {
                out.add(arr != null && i < arr.size() ? arr.get(i) : null);
            }
        }
        return out;
    }

    /** "success paths" from "success paths, use cases, …"; "all scenario types for this part" from "… — …". */
    private static String shortFocus(String focus) {
        String s = focus;
        for (String sep : new String[]{" — ", ","}) {
            int at = s.indexOf(sep);
            if (at > 0) s = s.substring(0, at);
        }
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }
}
