
package com.atiksh.taskFlow.service;

import com.atiksh.taskFlow.entity.Job;
import com.atiksh.taskFlow.entity.JobAttempt;
import com.atiksh.taskFlow.repository.JobAttemptRepository;
import com.atiksh.taskFlow.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class JobCompletionService {



    private final JobRepository jobRepository;
    private final JobAttemptRepository jobAttemptRepository;
    private final RetryPolicy retryPolicy;
    public JobCompletionService(
            JobRepository jobRepository,
            JobAttemptRepository jobAttemptRepository,
            RetryPolicy retryPolicy
    ) {
        this.jobRepository = jobRepository;
        this.jobAttemptRepository = jobAttemptRepository;
        this.retryPolicy = retryPolicy;
    }

    @Transactional
    public void markSucceeded(
            UUID jobId,
            String workerId,
            int attemptNumber
    ) {
        Job job = findJobForUpdate(jobId);
        JobAttempt attempt = findAttempt(jobId, attemptNumber);

        requireCurrentOwner(job, attempt, workerId, attemptNumber);

        attempt.markSucceeded();
        job.markSucceeded();
    }

    @Transactional
    public void markFailed(
            UUID jobId,
            String workerId,
            int attemptNumber,
            String errorMessage
    ) {
        Job job = findJobForUpdate(jobId);
        JobAttempt attempt = findAttempt(jobId, attemptNumber);

        requireCurrentOwner(job, attempt, workerId, attemptNumber);

        String safeError = sanitizeError(errorMessage);
        attempt.markFailed(safeError);

        if (job.getAttemptCount() >= job.getMaxAttempts()) {
            job.markDeadLettered(safeError);
        } else {
            long delaySeconds =
                    retryPolicy.calculateRetryDelay(job.getAttemptCount());

            job.scheduleRetry(safeError, delaySeconds);
        }
    }

    private Job findJobForUpdate(UUID jobId) {
        return jobRepository.findByIdForUpdate(jobId)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Job not found: " + jobId
                        ));
    }

    private JobAttempt findAttempt(
            UUID jobId,
            int attemptNumber
    ) {
        return jobAttemptRepository
                .findByJobIdAndAttemptNumber(jobId, attemptNumber)
                .orElseThrow(() ->
                        new IllegalArgumentException(
                                "Attempt not found for job " + jobId
                        ));
    }

    private void requireCurrentOwner(
            Job job,
            JobAttempt attempt,
            String workerId,
            int attemptNumber
    ) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalStateException("Worker ID is required");
        }

        if (job.getStatus() != Job.Status.RUNNING
                || !"RUNNING".equals(attempt.getStatus())) {
            throw new IllegalStateException(
                    "Job and attempt must both be RUNNING"
            );
        }

        if (job.getAttemptCount() != attemptNumber) {
            throw new IllegalStateException(
                    "Attempt number is no longer current"
            );
        }

        if (!workerId.equals(job.getWorkerId())) {
            throw new IllegalStateException(
                    "Worker no longer owns this job"
            );
        }

        if (!jobRepository.hasValidLease(job.getId(), workerId, attemptNumber)) {
            throw new IllegalStateException(
                    "Worker lease has expired or is no longer valid"
            );
        }
    }

    private String sanitizeError(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return "Job execution failed";
        }

        String sanitized = errorMessage
                .replaceAll("[\\r\\n\\t]+", " ")
                .trim();

        return sanitized.length() > 1000
                ? sanitized.substring(0, 1000)
                : sanitized;
    }
}
