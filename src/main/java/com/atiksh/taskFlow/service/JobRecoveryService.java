
package com.atiksh.taskFlow.service;

import com.atiksh.taskFlow.entity.Job;
import com.atiksh.taskFlow.entity.JobAttempt;
import com.atiksh.taskFlow.repository.JobAttemptRepository;
import com.atiksh.taskFlow.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobRecoveryService {

    private final JobRepository jobRepository;
    private final JobAttemptRepository jobAttemptRepository;
    private final RetryPolicy retryPolicy;

    public JobRecoveryService(
            JobRepository jobRepository,
            JobAttemptRepository jobAttemptRepository,
            RetryPolicy retryPolicy) {
        this.jobRepository = jobRepository;
        this.jobAttemptRepository = jobAttemptRepository;
        this.retryPolicy = retryPolicy;
    }

    @Transactional
    public boolean recoverNextExpiredJob() {
        var expiredJobOptional =
                jobRepository.findNextExpiredJobForRecovery();

        if (expiredJobOptional.isEmpty()) {
            return false;
        }

        Job job = expiredJobOptional.get();

        JobAttempt attempt = jobAttemptRepository
                .findByJobIdAndAttemptNumber(
                        job.getId(),
                        job.getAttemptCount())
                .orElseThrow(() -> new IllegalStateException(
                        "No attempt found for expired job " + job.getId()));

        if (!"RUNNING".equals(attempt.getStatus())) {
            throw new IllegalStateException(
                    "Attempt is not RUNNING for expired job " + job.getId());
        }

        String errorMessage = "Worker lease expired before job completion";
        attempt.markFailed(errorMessage);

        if (job.getAttemptCount() >= job.getMaxAttempts()) {
            job.markDeadLettered(errorMessage);
        } else {
            long delaySeconds =
                    retryPolicy.calculateRetryDelay(job.getAttemptCount());

            job.scheduleRetry(errorMessage, delaySeconds);
        }

        return true;
    }
}
