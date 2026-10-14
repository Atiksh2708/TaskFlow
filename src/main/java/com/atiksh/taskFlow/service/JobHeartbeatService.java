
package com.atiksh.taskFlow.service;

import com.atiksh.taskFlow.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class JobHeartbeatService {

    private final JobRepository jobRepository;

    public JobHeartbeatService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Transactional
    public boolean renewLease(
            UUID jobId,
            String workerId,
            int attemptNumber
    ) {
        return jobRepository.renewLease(
                jobId,
                workerId,
                attemptNumber
        ) == 1;
    }
}
