
package com.atiksh.taskFlow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tools.jackson.databind.JsonNode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jobs")
public class Job {

    public enum Status {
        QUEUED,
        SCHEDULED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        RETRY_WAIT,
        DEAD_LETTERED,
        CANCELLED
    }

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "job_type", nullable = false, length = 100)
    private String jobType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private Status status = Status.QUEUED;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts = 3;

    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    @Column(name = "last_error")
    private String lastError;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "result", columnDefinition = "jsonb")
    private JsonNode result;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "worker_id", length = 100)
    private String workerId;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    protected Job() {
        // Required by JPA.
    }

    public Job(
            UUID id,
            UUID userId,
            String jobType,
            JsonNode payload,
            Instant scheduledAt,
            int maxAttempts,
            String idempotencyKey
    ) {
        this.id = id;
        this.userId = userId;
        this.jobType = jobType;
        this.payload = payload;
        this.scheduledAt = scheduledAt;
        this.availableAt = scheduledAt != null
                ? scheduledAt
                : Instant.now();
        this.idempotencyKey = idempotencyKey;
        this.status = scheduledAt != null
                && scheduledAt.isAfter(Instant.now())
                ? Status.SCHEDULED
                : Status.QUEUED;
        this.attemptCount = 0;
        this.maxAttempts = maxAttempts;;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getJobType() {
        return jobType;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public Instant getAvailableAt() {
        return availableAt;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getLastError() {
        return lastError;
    }

    public JsonNode getResult() {
        return result;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void markRunning(String workerId, Instant leaseExpiry) {
        if (this.status != Status.QUEUED
                && this.status != Status.SCHEDULED
                && this.status != Status.RETRY_WAIT) {
            throw new IllegalStateException(
                    "Job cannot be marked as running from status: " + this.status
            );
        }

        this.status = Status.RUNNING;
        this.attemptCount++;

        Instant now = Instant.now();
        this.startedAt = now;
        this.updatedAt = now;

        this.workerId = workerId;
        this.leaseExpiresAt = leaseExpiry;
    }
    public void markSucceeded() {
        if (status != Status.RUNNING) {
            throw new IllegalStateException(
                    "Cannot mark job as SUCCEEDED from status " + status
            );
        }

        status = Status.SUCCEEDED;
        completedAt = Instant.now();
        updatedAt = completedAt;
        this.workerId = null;
        this.leaseExpiresAt = null;
    }
    public void scheduleRetry(String errorMessage, long delaySeconds) {
        if (status != Status.RUNNING) {
            throw new IllegalStateException(
                    "Cannot schedule retry from status " + status
            );
        }

        if (attemptCount >= maxAttempts) {
            markDeadLettered(errorMessage);
            return;
        }

        status = Status.RETRY_WAIT;
        lastError = errorMessage;
        availableAt = Instant.now().plusSeconds(delaySeconds);
        updatedAt = Instant.now();
        this.workerId = null;
        this.leaseExpiresAt = null;
    }

    public void markDeadLettered(String errorMessage) {
        if (status != Status.RUNNING) {
            throw new IllegalStateException(
                    "Cannot dead-letter job from status " + status
            );
        }

        status = Status.DEAD_LETTERED;
        lastError = errorMessage;
        completedAt = Instant.now();
        updatedAt = completedAt;
        this.workerId = null;
        this.leaseExpiresAt = null;
    }
    public String getWorkerId() {
        return workerId;
    }

    public Instant getLeaseExpiresAt() {
        return leaseExpiresAt;
    }
}
