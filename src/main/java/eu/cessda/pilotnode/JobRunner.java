package eu.cessda.pilotnode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.logging.Logger;

@Service
public class JobRunner {
    private static final Logger log = Logger.getLogger(JobRunner.class.getName());

    private final Executor executor;

    @Autowired
    public JobRunner(Executor executor) {
        this.executor = executor;
    }

    private static void run(JobRecord jobRecord, ThrowableRunnable<?> callable) {
        assert jobRecord.getStatus() == JobRecord.Status.QUEUED;

        // Mark task as running
        jobRecord.markRunning();

        try {
            callable.call(jobRecord);
            if (jobRecord.getStatus() == JobRecord.Status.RUNNING) {
                jobRecord.markDone("");
            }
        } catch (Exception e) {
            log.warning(jobRecord.getType() + " failed: " + e.getMessage());
            jobRecord.markError(e.getMessage());
        }
    }

    public JobRecord start(String type, ThrowableRunnable<?> runnable) {
        // Create job record object
        JobRecord rec = new JobRecord(type);

        // Submit the job to the executor
        executor.execute(() -> JobRunner.run(rec, runnable));

        // Return the job record object
        return rec;
    }

    @FunctionalInterface
    public interface ThrowableRunnable<T extends Exception> {
        /**
         * Runs this operation, or throws an exception if unable to do so.
         *
         * @throws T if unable to compute a result
         */
        void call(JobRecord jobRecord) throws T;
    }
}
