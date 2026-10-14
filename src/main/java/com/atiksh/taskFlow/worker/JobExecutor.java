
package com.atiksh.taskFlow.worker;

import com.atiksh.taskFlow.entity.Job;
import org.springframework.stereotype.Component;

@Component
public class JobExecutor {

    public void execute(Job job) {
        switch (job.getJobType()) {
            case "SEND_EMAIL" -> simulateEmail(job);
            default -> throw new IllegalArgumentException(
                    "Unsupported job type: " + job.getJobType()
            );
        }
    }

    private void simulateEmail(Job job) {
        String recipient = job.getPayload()
                .get("recipient")
                .asString();

        if (recipient == null || recipient.isBlank()) {
            throw new IllegalArgumentException(
                    "Email recipient is required"
            );
        }

        // Simulate work without sending a real email.
        System.out.println(
                "Simulated email job " + job.getId()
                        + " for recipient " + recipient
        );
    }
}
