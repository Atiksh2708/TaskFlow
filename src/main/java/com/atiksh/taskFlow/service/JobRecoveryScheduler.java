package com.atiksh.taskFlow.service;

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
public class JobRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobRecoveryScheduler.class);

    private static final int MAX_RECOVERY_BATCH_SIZE = 100;

    private final JobRecoveryService jobRecoveryService;

    public JobRecoveryScheduler(JobRecoveryService jobRecoveryService) {
        this.jobRecoveryService = jobRecoveryService;
    }

    @Scheduled(fixedDelayString = "${taskflow.worker.recovery-interval-ms:5000}")
    public void recoverExpiredJobs() {
        int recovered = 0;

        try {
            while (recovered < MAX_RECOVERY_BATCH_SIZE
                    && jobRecoveryService.recoverNextExpiredJob()) {
                recovered++;
            }

            if (recovered > 0) {
                log.info("Recovered {} expired job lease(s)", recovered);
            }
        } catch (Exception exception) {
            // A later scheduled run can retry remaining expired jobs.
            log.error("Error while recovering expired job leases", exception);
        }
    }
}
