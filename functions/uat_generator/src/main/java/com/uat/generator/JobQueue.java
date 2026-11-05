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
                            .setJobName(jobName + "_" + key)
                            .setJobpoolName(pool)
                            .build();
            com.zc.component.jobscheduling.beans.job.ZCJobDetails job =
                    com.zc.component.jobscheduling.ZCJobScheduling.getInstance().job.submitJob(meta);
            if (job != null && job.getJobId() != null) out.put("job_id", String.valueOf(job.getJobId()));
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
        return (ObjectNode) MAPPER.readTree(s);
    }

    static ObjectNode error(int status, String msg) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("status", status);
        o.put("error", msg);
        return o;
    }
}
