
package com.atiksh.taskFlow.worker;

import com.atiksh.taskFlow.entity.Job;
import com.atiksh.taskFlow.service.JobClaimService;
import com.atiksh.taskFlow.service.JobCompletionService;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class JobWorker {

    private final JobClaimService jobClaimService;
    private final JobCompletionService jobCompletionService;
    private final JobExecutor jobExecutor;

    public JobWorker(
            JobClaimService jobClaimService,
            JobCompletionService jobCompletionService,
            JobExecutor jobExecutor
    ) {
        this.jobClaimService = jobClaimService;
        this.jobCompletionService = jobCompletionService;
        this.jobExecutor = jobExecutor;
    }

    public boolean processOneJob() {
        Optional<Job> claimed = jobClaimService.claimNextJob();

        if (claimed.isEmpty()) {
            return false;
        }

        Job job = claimed.get();
        int attemptNumber = job.getAttemptCount();

        try {
            jobExecutor.execute(job);
        } catch (Exception exception) {
            jobCompletionService.markFailed(
                    job.getId(),
                    job.getWorkerId(),
                    attemptNumber,
                    exception.getMessage()
            );
            return true;
        }

        jobCompletionService.markSucceeded(
                job.getId(),
                job.getWorkerId(),
                attemptNumber
        );

        return true;
    }
}
