package com.atiksh.taskFlow.service;

import com.atiksh.taskFlow.entity.Job;
import com.atiksh.taskFlow.exception.IdempotencyConflictException;
import com.atiksh.taskFlow.service.JobNotFoundException;
import com.atiksh.taskFlow.repository.JobRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class JobService {

    private final JobRepository jobRepository;
    private final JobCreationTransactionService creationService;
    private final IdempotencyConstraintDetector constraintDetector;

    public JobService(
            JobRepository jobRepository,
            JobCreationTransactionService creationService,
            IdempotencyConstraintDetector constraintDetector
    ) {
        this.jobRepository = jobRepository;
        this.creationService = creationService;
        this.constraintDetector = constraintDetector;
    }

    public Job createJob(
            UUID userId,
            String jobType,
            JsonNode payload,
            Instant scheduledAt,
            Integer requestedMaxAttempts,
            String idempotencyKey
    ) {
        int maxAttempts = requestedMaxAttempts != null
                ? requestedMaxAttempts
                : 3;

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return creationService.create(
                    userId, jobType, payload, scheduledAt,
                    maxAttempts, idempotencyKey
            );
        }

        var existing = jobRepository.findByUserIdAndIdempotencyKey(
                userId, idempotencyKey
        );

        if (existing.isPresent()) {
            return returnIfSameRequest(
                    existing.get(), jobType, payload,
                    scheduledAt, maxAttempts
            );
        }

        try {
            return creationService.create(
                    userId, jobType, payload, scheduledAt,
                    maxAttempts, idempotencyKey
            );
        } catch (DataIntegrityViolationException ex) {
            if (!constraintDetector.isIdempotencyConflict(ex)) {
                throw ex;
            }

            // The creation transaction has rolled back by this point.
            var winner = jobRepository.findByUserIdAndIdempotencyKey(
                    userId, idempotencyKey
            );

            if (winner.isEmpty()) {
                // Preserve the original database error if recovery
                // cannot find the row that caused the conflict.
                throw ex;
            }

            return returnIfSameRequest(
                    winner.get(), jobType, payload,
                    scheduledAt, maxAttempts
            );
        }
    }

    private Job returnIfSameRequest(
            Job existing,
            String jobType,
            JsonNode payload,
            Instant scheduledAt,
            int maxAttempts
    ) {
        boolean sameRequest =
                Objects.equals(existing.getJobType(), jobType)
                        && Objects.equals(existing.getPayload(), payload)
                        && Objects.equals(existing.getScheduledAt(), scheduledAt)
                        && existing.getMaxAttempts() == maxAttempts;

        if (!sameRequest) {
            throw new IdempotencyConflictException(
                    "Idempotency key was already used with a different request."
            );
        }

        return existing;
    }

    @Transactional(readOnly = true)
    public Job getJob(UUID id) {
        return jobRepository.findById(id)
                .orElseThrow(() -> new JobNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<Job> getJobsByUser(UUID userId, Pageable pageable) {
        return jobRepository.findByUserId(userId, pageable);
    }
}
