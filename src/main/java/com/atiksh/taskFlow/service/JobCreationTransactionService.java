package com.atiksh.taskFlow.service;

import com.atiksh.taskFlow.entity.Job;
import com.atiksh.taskFlow.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

@Service
public class JobCreationTransactionService {

    private final JobRepository jobRepository;

    public JobCreationTransactionService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Transactional
    public Job create(
            UUID userId,
            String jobType,
            JsonNode payload,
            Instant scheduledAt,
            int maxAttempts,
            String idempotencyKey
    ) {
        Job job = new Job(
                UUID.randomUUID(),
                userId,
                jobType,
                payload,
                scheduledAt,
                maxAttempts,
                idempotencyKey
        );

        // Force SQL execution here so uniqueness violations occur
        // inside this method, before the transaction proxy returns.
        return jobRepository.saveAndFlush(job);
    }
}
