
package com.atiksh.taskFlow.dto;

import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record CreateJobRequest(
        @NotNull
        UUID userId,

        @NotBlank
        @Size(max = 100)
        String jobType,

        @NotNull JsonNode payload,

        Instant scheduledAt,

        @Min(1)
        @Max(10)
        Integer maxAttempts,

        @Size(max = 255)
        String idempotencyKey
) {}
