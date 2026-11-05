import com.catalyst.Context;
import com.catalyst.job.CatalystJobHandler;
import com.catalyst.job.JOB_STATUS;
import com.catalyst.job.JobRequest;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Level;

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
        context.log("LiftOff worker starting job " + key, Level.INFO);
        try {
            boolean ok = com.uat.generator.ImplJob.run(key, () -> context.getRemainingExecutionTimeMs());
            context.log("LiftOff worker finished job " + key + (ok ? " (success)" : " (failed; reason saved)"), Level.INFO);
            return ok ? JOB_STATUS.SUCCESS : JOB_STATUS.FAILURE;
        } catch (Throwable t) {
            // Usually a class that couldn't load from lib/: log it in full, then try to save the reason.
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            context.log("LiftOff worker crashed on job " + key + ": " + sw, Level.SEVERE);
            try { com.uat.generator.ImplJob.fail(key, t); } catch (Throwable ignored) { }
            return JOB_STATUS.FAILURE;
        }
    }
}
