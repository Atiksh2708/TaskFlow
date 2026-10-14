package com.atiksh.taskFlow.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "taskflow.worker",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class JobPollingScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobPollingScheduler.class);

    private final JobWorker jobWorker;

    public JobPollingScheduler(JobWorker jobWorker) {
        this.jobWorker = jobWorker;
    }

    @Scheduled(fixedDelayString = "${taskflow.worker.poll-interval-ms:500}")
    public void pollForJobs() {
        try {
            jobWorker.processOneJob();
        } catch (Exception exception) {
            // Keep a transient failure from disabling future scheduled polling.
            log.error("Unexpected error while polling for a job", exception);
        }
    }
}
