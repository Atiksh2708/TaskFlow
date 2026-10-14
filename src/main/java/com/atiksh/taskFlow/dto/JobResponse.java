
package com.atiksh.taskFlow.dto;

import com.atiksh.taskFlow.entity.Job;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record JobResponse(
        UUID id,
        UUID userId,
        String jobType,
        tools.jackson.databind.JsonNode payload,
        Job.Status status,
        Instant scheduledAt,
        Instant createdAt,
        Instant updatedAt,
        int attemptCount,
        int maxAttempts,
        String lastError
) {
    public static JobResponse from(Job job) {
        return new JobResponse(
                job.getId(),
                job.getUserId(),
                job.getJobType(),
                job.getPayload(),
                job.getStatus(),
                job.getScheduledAt(),
                job.getCreatedAt(),
                job.getUpdatedAt(),
                job.getAttemptCount(),
                job.getMaxAttempts(),
                job.getLastError()
        );
    }
}
