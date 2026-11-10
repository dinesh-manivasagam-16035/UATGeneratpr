package com.uat.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Queues and reads background jobs on the impl_worker job function, for every job kind
 * (blueprint — ImplJob; uat_generate and uat_execute — UatJob). The job's input must already be
 * in JobStore under ImplJob.inputKey(key); the worker reads its "kind" and dispatches.
 *
 * Env: IMPL_JOBPOOL (default "liftoffjobs"), IMPL_WORKER_FUNCTION (default "impl_worker").
 *      LIFTOFF_JOBSTORE=memory runs the job in this process instead (local tests only).
 */
final class JobQueue {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JobQueue() {}

    /**
     * Saves the input and the "queued" status, then queues the worker.
     * Returns {job_key, job_id?}, or {status: 503, error} when Cache or the job scheduler isn't set up.
     */
    static ObjectNode submit(String key, String jobName, ObjectNode input, int total) {
        // IMPL_JOBS=off on uat_generator: never queue the worker; the browser runs the short
        // requests itself (each agent call is now a few seconds, well inside the ~30 s limit).
        String jobs = System.getenv("IMPL_JOBS");
        if (jobs != null && ("off".equalsIgnoreCase(jobs.trim()) || "0".equals(jobs.trim()) || "false".equalsIgnoreCase(jobs.trim()))) {
            return error(503, "Background jobs are turned off (IMPL_JOBS=off); generating in the browser instead.");
        }
        try {
            JobStore.put(ImplJob.inputKey(key), MAPPER.writeValueAsString(input));
            ImplJob.status(key, "queued", 0, total, null, "Waiting for a worker");
        } catch (Exception e) {
            return error(503, "Couldn't save the job to Catalyst Cache (" + ImplementServlet.trim(e.getMessage(), 200)
                    + "). Background jobs need Cache enabled on the project.");
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("job_key", key);
        if ("memory".equalsIgnoreCase(System.getenv("LIFTOFF_JOBSTORE"))) {
            // Local tests: no Catalyst job scheduler; run the worker in this process.
            new Thread(() -> ImplJob.run(key, () -> 15 * 60_000L), "impl-worker-local").start();
            return out;
        }
        String pool = System.getenv().getOrDefault("IMPL_JOBPOOL", "liftoffjobs");
        String target = System.getenv().getOrDefault("IMPL_WORKER_FUNCTION", "impl_worker");
        try {
            org.json.simple.JSONObject params = new org.json.simple.JSONObject();
            params.put("job_key", key);
            com.zc.common.ZCProject.initProject();
            com.zc.component.jobscheduling.beans.job.ZCJobMetaDetail meta =
                    com.zc.component.jobscheduling.beans.job.ZCJobBuilder.functionJobBuilder()
                            .setTargetName(target)
                            .setParams(params)
                            .setJobName(jobLabel(jobName, key))
                            .setJobpoolName(pool)
                            .build();
            com.zc.component.jobscheduling.beans.job.ZCJobDetails job =
                    com.zc.component.jobscheduling.ZCJobScheduling.getInstance().job.submitJob(meta);
            if (job != null && job.getJobId() != null) {
                out.put("job_id", String.valueOf(job.getJobId()));
                // Kept with the status so a poll can ask Catalyst whether the job died before it started writing.
                try {
                    ObjectNode st = (ObjectNode) MAPPER.readTree(JobStore.get(ImplJob.statusKey(key)));
                    if ("queued".equals(st.path("state").asText())) {
                        st.put("job_id", String.valueOf(job.getJobId()));
                        JobStore.put(ImplJob.statusKey(key), MAPPER.writeValueAsString(st));
                    }
                } catch (Exception ignored) { /* the poll falls back to the 3-minute check */ }
            }
            return out;
        } catch (Exception e) {
            return error(503, "Couldn't queue the background job (" + ImplementServlet.trim(e.getMessage(), 200)
                    + "). Check that the job function '" + target + "' is deployed and a Function job pool named '"
                    + pool + "' exists (see agent/ZIA_GENERATOR.md).");
        }
    }

    /** The job's status {state, done, total, label?, error?, steps?}, or {status, error} for a bad or expired key. */
    static ObjectNode status(String key) throws Exception {
        if (!JobStore.validKey(key)) return error(400, "Invalid job_key.");
        String s = JobStore.get(ImplJob.statusKey(key));
        if (s == null) return error(404, "No such job, or it has expired.");
        ObjectNode st = (ObjectNode) MAPPER.readTree(s);
        // Still "queued" but the worker may have crashed before writing anything: ask Catalyst.
        if ("queued".equals(st.path("state").asText()) && st.hasNonNull("job_id")) {
            String catalyst = catalystState(st.path("job_id").asText());
            if (catalyst != null) {
                st.put("catalyst_state", catalyst);
                String c = catalyst.toLowerCase();
                if (c.contains("fail") || c.contains("error") || c.contains("success") || c.contains("complete")) {
                    st.put("state", "failed");
                    st.put("error", "The worker job ended (" + catalyst + ") without saving progress. Open Catalyst → "
                            + "Job Scheduling → Jobs → the job ending in " + key + " → View Logs for the reason.");
                }
            }
        }
        return st;
    }

    /** Catalyst's own status for a job id (e.g. "Failed"), or null when it can't be read. */
    private static String catalystState(String jobId) {
        try {
            com.zc.common.ZCProject.initProject();
            com.zc.component.jobscheduling.beans.job.ZCJobDetails d =
                    com.zc.component.jobscheduling.ZCJobScheduling.getInstance().job.getJob(Long.parseLong(jobId));
            return d == null ? null : d.getJobStatus();
        } catch (Exception e) {
            return null;
        }
    }

    static ObjectNode error(int status, String msg) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("status", status);
        o.put("error", msg);
        return o;
    }

    /**
     * Catalyst job names are limited to 20 characters: the kind's initials plus the key
     * ("blueprint" → "b_jx…", "uat_generate" → "ug_jx…").
     */
    static String jobLabel(String kind, String key) {
        StringBuilder sb = new StringBuilder();
        for (String w : kind.split("_")) if (!w.isEmpty()) sb.append(w.charAt(0));
        String name = sb + "_" + key;
        return name.length() > 20 ? name.substring(0, 20) : name;
    }
}
