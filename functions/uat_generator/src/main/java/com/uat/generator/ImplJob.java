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
 * The slow half of blueprint generation, run by the impl_worker job function (up to 15 min)
 * instead of an Advanced I/O request (~30 s). Same calls as the "part" and "plan" stages of
 * ImplementStageServlet; inputs, progress and results go through JobStore.
 *
 * Cache entries for job key K:
 *   K + "i"   input   {kind: blueprint (default) | uat_generate | uat_execute, ...}; blueprint:
 *                     {brd, modules, answers, org_text, jobs:[{part, group}]}. Other kinds go to UatJob.
 *   K + "s"   status  {state: queued|running|done|failed, done, total, error?, label?,
 *                      steps:[{label, state: waiting|working|done|failed, seconds?}]}
 *   K + "r" + n  result of jobs[n] (the "part" stage response)
 *   K + "p"   plan    {items:[...], warnings:[...]}
 */
public final class ImplJob {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ImplJob() {}

    public static String inputKey(String k)  { return k + "i"; }
    public static String statusKey(String k) { return k + "s"; }
    public static String resultKey(String k, int n) { return k + "r" + n; }
    public static String planKey(String k)   { return k + "p"; }

    /** Entry point for the impl_worker job function. remainingMs = time the job has left. */
    public static boolean run(String key, LongSupplier remainingMs) {
        try {
            if (!JobStore.validKey(key)) throw new IllegalArgumentException("bad job key");
            String raw = JobStore.get(inputKey(key));
            if (raw == null) throw new IllegalStateException("The job's input has expired.");
            JsonNode in = MAPPER.readTree(raw);
            // Each function has its own env vars: say so plainly if this worker has no LLM set up.
            if (!Llm.mock() && !Llm.available()) {
                throw new IllegalStateException("impl_worker: " + Llm.notConfiguredMessage());
            }
            // One worker for every job kind: UAT generation and execution live in UatJob.
            String kind = in.path("kind").asText("blueprint");
            if ("uat_generate".equals(kind)) return UatJob.generate(key, in, remainingMs);
            if ("uat_execute".equals(kind))  return UatJob.execute(key, in, remainingMs);
            ArrayNode jobs = (ArrayNode) in.path("jobs");
            int total = jobs.size() + 1;                         // + the plan
            Steps steps = new Steps(jobs);
            status(key, "running", 0, total, null, "Starting", steps);
            // Calls here aren't bound by Catalyst's 30 s request limit.
            ZiaAgentClient.timeoutSecOverride = ImplementServlet.envInt("IMPL_WORKER_CALL_TIMEOUT_SEC", 240);

            int[] done = {0};
            ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(2, jobs.size())));
            try {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < jobs.size(); i++) {
                    final int n = i;
                    futures.add(pool.submit(() -> {
                        JsonNode j = jobs.get(n);
                        steps.start(n);
                        synchronized (done) { status(key, "running", done[0], total, null, steps.label(n), steps); }
                        ObjectNode body = ((ObjectNode) in).deepCopy();
                        body.remove("jobs");
                        body.put("part", j.path("part").asInt());
                        body.put("group", j.path("group").asText());
                        body.put("deadline_sec", 200);
                        ObjectNode r = ImplementStageServlet.part(body);
                        JobStore.put(resultKey(key, n), MAPPER.writeValueAsString(r));
                        steps.finish(n, r.hasNonNull("error") && !r.path("error").asText("").isEmpty(), count(r));
                        synchronized (done) {
                            done[0]++;
                            status(key, "running", done[0], total, null, steps.label(n), steps);
                        }
                        return null;
                    }));
                }
                for (Future<?> f : futures) {
                    long left = Math.max(1000, remainingMs.getAsLong() - 90_000);   // keep time for the plan
                    f.get(left, TimeUnit.MILLISECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            // Plan, from the merged parts (ids match what "finish" will assign: same merge order).
            List<JsonNode> results = new ArrayList<>();
            for (int i = 0; i < jobs.size(); i++) results.add(MAPPER.readTree(JobStore.get(resultKey(key, i))));
            List<String> warnings = new ArrayList<>();
            ObjectNode spec = ImplementStageServlet.merge(results, warnings);
            ObjectNode plan = MAPPER.createObjectNode();
            ArrayNode planItems = plan.putArray("items");
            ArrayNode planWarnings = plan.putArray("warnings");
            if (ImplementServlet.hasBuildItems(spec)) {
                steps.start(jobs.size());
                status(key, "running", done[0], total, null, steps.label(jobs.size()), steps);
                ObjectNode pb = MAPPER.createObjectNode();
                pb.set("spec", spec);
                pb.set("requirements", requirementList(in.path("brd").asText("")));
                ObjectNode pr = ImplementStageServlet.plan(pb);
                for (JsonNode t : pr.path("spec").path("plan")) planItems.add(t);
                for (JsonNode w : pr.path("warnings")) planWarnings.add(w);
            }
            JobStore.put(planKey(key), MAPPER.writeValueAsString(plan));
            if (ImplementServlet.hasBuildItems(spec)) steps.finish(jobs.size(), false, planItems.size());
            else steps.skip(jobs.size(), "nothing to plan: no items were generated");
            status(key, "done", total, total, null, "Done", steps);
            return true;
        } catch (Throwable e) {
            // Throwable, not Exception: a missing class (NoClassDefFoundError) must still be reported.
            fail(key, e);
            return false;
        }
    }

    /** Marks the job failed with a short reason the Blueprint/UAT tabs can show. Never throws. */
    public static void fail(String key, Throwable e) {
        try {
            String why = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            status(key, "failed", 0, 0, why, null, null);
        } catch (Throwable ignored) {
            // Cache unreachable: the job's own logs (Catalyst → Job Scheduling → Jobs → View Logs) have it.
        }
    }

    static ArrayNode requirementList(String brd) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (Requirements.Requirement r : ImplementStageServlet.requirements(brd)) {
            arr.addObject().put("id", r.id).put("title", r.title);
        }
        return arr;
    }

    /** Items a part produced, for the progress display. */
    private static int count(JsonNode r) {
        int n = 0;
        for (JsonNode list : r.path("items")) n += list.size();
        return n;
    }

    /** Per-step progress shown while the job runs: each BRD part's data model and automation, then the plan. */
    static final class Steps {
        private static final java.util.Map<String, String> WHAT = java.util.Map.of(
                "data_model", "Modules, fields, layouts, rules, roles",
                "automation", "Workflows, approvals, Deluge",
                "mock", "Offline sample");
        final ArrayNode arr = MAPPER.createArrayNode();
        final long[] started;

        /** Steps from {label, detail} pairs (other job kinds). */
        Steps(List<String[]> rows) {
            for (String[] r : rows) arr.addObject().put("label", r[0]).put("detail", r[1]).put("state", "waiting");
            started = new long[arr.size()];
        }

        Steps(ArrayNode jobs) {
            for (JsonNode j : jobs) {
                String g = j.path("group").asText();
                String name = g.equals("data_model") ? "Data model" : g.equals("automation") ? "Automation" : "Sample";
                arr.addObject().put("label", "Part " + j.path("part").asInt() + " · " + name)
                        .put("detail", WHAT.getOrDefault(g, "")).put("state", "waiting");
            }
            arr.addObject().put("label", "Delivery plan").put("detail", "Phases, tasks, effort").put("state", "waiting");
            started = new long[arr.size()];
        }
        synchronized void start(int i) {
            started[i] = System.currentTimeMillis();
            ((ObjectNode) arr.get(i)).put("state", "working").put("started_at", started[i]);   // live timer in the UI
        }
        synchronized void finish(int i, boolean failed, int items) {
            ObjectNode o = (ObjectNode) arr.get(i);
            o.put("state", failed ? "failed" : "done");
            o.put("seconds", Math.round((System.currentTimeMillis() - started[i]) / 1000.0));
            o.put("items", items);
        }
        /** Adds a field to step i (e.g. a case's result). */
        synchronized void set(int i, String field, String value) { ((ObjectNode) arr.get(i)).put(field, value); }
        synchronized void skip(int i, String why) {
            ((ObjectNode) arr.get(i)).put("state", "skipped").put("detail", why);
        }
        synchronized String label(int i) { return arr.get(i).path("label").asText(); }
        synchronized ArrayNode copy() { return arr.deepCopy(); }
    }

    static void status(String key, String state, int done, int total, String error, String label) throws Exception {
        status(key, state, done, total, error, label, null);
    }

    static void status(String key, String state, int done, int total, String error, String label, Steps steps) throws Exception {
        ObjectNode s = MAPPER.createObjectNode();
        if (steps != null) s.set("steps", steps.copy());
        s.put("state", state);
        s.put("done", done);
        s.put("total", total);
        if (label != null) s.put("label", label);
        if (error != null) s.put("error", ImplementServlet.trim(error, 400));
        s.put("updated", System.currentTimeMillis());
        JobStore.put(statusKey(key), MAPPER.writeValueAsString(s));
    }
}
