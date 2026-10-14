
package com.atiksh.taskFlow.controller;

import com.atiksh.taskFlow.dto.CreateJobRequest;
import com.atiksh.taskFlow.dto.JobResponse;
import com.atiksh.taskFlow.entity.Job;
import com.atiksh.taskFlow.service.JobService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobResponse createJob(
           @Valid @RequestBody CreateJobRequest request
    ) {
        Job job = jobService.createJob(
                request.userId(),
                request.jobType(),
                request.payload(),
                request.scheduledAt(),
                request.maxAttempts(),
                request.idempotencyKey()
        );

        return JobResponse.from(job);
    }

    @GetMapping("/{id}")
    public JobResponse getJob(@PathVariable UUID id) {
        return JobResponse.from(jobService.getJob(id));
    }
    @GetMapping
    public Page<JobResponse> getJobs(
            @RequestParam UUID userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        var pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        return jobService.getJobsByUser(userId, pageable)
                .map(JobResponse::from);
    }
}
