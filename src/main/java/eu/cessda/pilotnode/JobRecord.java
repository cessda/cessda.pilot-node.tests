package eu.cessda.pilotnode;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Objects;

public final class JobRecord implements Comparable<JobRecord> {
    private final String jobId;
    private final String type;

    private Status status = Status.QUEUED;
    private OffsetDateTime startedAt = null;
    private OffsetDateTime finishedAt = null;
    private String message = null;

    JobRecord(String type) {
        this.jobId = type + "-" + System.currentTimeMillis();
        this.type = type;
    }

    void markRunning() {
        if (status == Status.QUEUED) {
            this.startedAt = OffsetDateTime.now();
            this.status = Status.RUNNING;
        } else {
            throw new IllegalStateException("Attempted to start job " + jobId + " with status "  + status + ". Only queued jobs may be started." );
        }
    }

    void markDone(String msg) {
        if (status != Status.RUNNING) {
            throw new IllegalStateException("Job " + jobId + " was not running." );
        }
        this.status = Status.DONE;
        this.message = msg;
        this.finishedAt = OffsetDateTime.now();
    }

    void markError(String msg) {
        if (status != Status.RUNNING) {
            throw new IllegalStateException("Job " + jobId + " was not running." );
        }
        this.status = Status.ERROR;
        this.message = msg;
        this.finishedAt = OffsetDateTime.now();
    }

    public String getJobId() {
        return jobId;
    }

    public String getType() {
        return type;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public Status getStatus() {
        return status;
    }

    public OffsetDateTime getFinishedAt() {
        return finishedAt;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof JobRecord jobRecord)) return false;
        return Objects.equals(jobId, jobRecord.jobId)
                && Objects.equals(type, jobRecord.type)
                && Objects.equals(startedAt, jobRecord.startedAt)
                && status == jobRecord.status
                && Objects.equals(finishedAt, jobRecord.finishedAt)
                && Objects.equals(message, jobRecord.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(jobId, type, startedAt, status, finishedAt, message);
    }

    @Override
    public int compareTo(JobRecord o) {
        return Comparator.comparing(JobRecord::getStartedAt).compare(this, o);
    }

    public enum Status { QUEUED, RUNNING, DONE, ERROR }
}
