package eu.cessda.pilotnode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;
import java.util.logging.Logger;

@Service
public class JobRunner {
    private static final Logger log = Logger.getLogger(JobRunner.class.getName());

    private final Executor executor;

    // Spring Boot registers a second Executor bean ("taskScheduler", for
    // @Scheduled methods — see CheckAllScheduler) once @EnableScheduling is
    // on, so a plain Executor parameter is ambiguous. Job execution has
    // nothing to do with the cron scheduler; it should always use the
    // general-purpose "applicationTaskExecutor" Spring Boot provides by
    // default, so that's pinned explicitly here.
    @Autowired
    public JobRunner(@Qualifier("applicationTaskExecutor") Executor executor) {
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
