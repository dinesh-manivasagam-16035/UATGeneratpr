import com.catalyst.Context;
import com.catalyst.job.CatalystJobHandler;
import com.catalyst.job.JOB_STATUS;
import com.catalyst.job.JobRequest;

/**
 * LiftOff background worker (Catalyst job function). uat_generator queues a job in the
 * "liftoffjobs" pool with a job_key; this runs that generation with long agent timeouts
 * (up to 15 minutes) and writes progress and results to Catalyst Cache.
 * See com.uat.generator.ImplJob and agent/ZIA_GENERATOR.md.
 */
public class ImplWorker implements CatalystJobHandler {

    @Override
    public JOB_STATUS handleJobExecute(JobRequest request, Context context) throws Exception {
        String key = String.valueOf(request.getJobParam("job_key"));
        boolean ok = com.uat.generator.ImplJob.run(key, () -> context.getRemainingExecutionTimeMs());
        return ok ? JOB_STATUS.SUCCESS : JOB_STATUS.FAILURE;
    }
}
