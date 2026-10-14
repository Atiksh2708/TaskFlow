
package com.atiksh.taskFlow.repository;
import java.util.Optional;
import com.atiksh.taskFlow.entity.JobAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JobAttemptRepository
        extends JpaRepository<JobAttempt, UUID> {

    List<JobAttempt> findByJobIdOrderByAttemptNumberAsc(UUID jobId);
    Optional<JobAttempt> findByJobIdAndAttemptNumber(
            UUID jobId,
            int attemptNumber
    );
}
