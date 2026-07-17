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

    public JobRecord start(String type, ThrowableRunnable runnable) {
        // Create job record object
        JobRecord rec = new JobRecord(type);

        // Submit the job to the executor
        executor.execute(() -> JobRunner.run(rec, runnable));

        // Return the job record object
        return rec;
    }

    private static void run(JobRecord record, ThrowableRunnable callable) {
        assert record.getStatus() == JobRecord.Status.QUEUED;

        // Mark task as running
        record.markRunning();

        try {
            callable.call(record);
            if (record.getStatus() == JobRecord.Status.RUNNING) {
                record.markDone("");
            }
        } catch (Exception e) {
            log.warning(record.getType() + " failed: " + e.getMessage());
            record.markError(e.getMessage());
        }
    }

    @FunctionalInterface
    public interface ThrowableRunnable {
        /**
         * Runs this operation, or throws an exception if unable to do so.
         *
         * @throws Exception if unable to compute a result
         */
        void call(JobRecord record) throws Exception;
    }
}
