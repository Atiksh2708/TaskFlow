
package com.atiksh.taskFlow.service;

import com.atiksh.taskFlow.entity.Job;
import com.atiksh.taskFlow.entity.JobAttempt;
import com.atiksh.taskFlow.repository.JobAttemptRepository;
import com.atiksh.taskFlow.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import java.util.Optional;
import java.time.Instant;

@Service
public class JobClaimService {

    private static final long LEASE_DURATION_SECONDS = 30;

    private final String workerId = "worker-" + UUID.randomUUID();
    private final JobRepository jobRepository;
    private final JobAttemptRepository jobAttemptRepository;

    public JobClaimService(
            JobRepository jobRepository,
            JobAttemptRepository jobAttemptRepository
    ) {
        this.jobRepository = jobRepository;
        this.jobAttemptRepository = jobAttemptRepository;
    }

    @Transactional
    public Optional<Job> claimNextJob() {
        Optional<Job> nextJob = jobRepository.findNextJobToClaim();

        if (nextJob.isEmpty()) {
            return Optional.empty();
        }
        Job job = nextJob.get();
        Instant leaseExpiry = jobRepository.newLeaseExpiry();
        job.markRunning(workerId, leaseExpiry);

        jobRepository.save(job);

        JobAttempt attempt = new JobAttempt(
                job.getId(),
                job.getAttemptCount()
        );

        jobAttemptRepository.save(attempt);

        return Optional.of(job);
    }
}
