
package com.atiksh.taskFlow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "job_attempts")
public class JobAttempt {

    @Id
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message")
    private String errorMessage;

    protected JobAttempt() {
        // Required by JPA.
    }

    public JobAttempt(UUID jobId, int attemptNumber) {
        this.id = UUID.randomUUID();
        this.jobId = jobId;
        this.attemptNumber = attemptNumber;
        this.status = "RUNNING";
        this.startedAt = Instant.now();
    }

    public void markSucceeded() {
        this.status = "SUCCEEDED";
        this.completedAt = Instant.now();
        this.errorMessage = null;
    }

    public void markFailed(String errorMessage) {
        this.status = "FAILED";
        this.completedAt = Instant.now();
        this.errorMessage = errorMessage;
    }

    public UUID getId() {
        return id;
    }

    public UUID getJobId() {
        return jobId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public String getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
