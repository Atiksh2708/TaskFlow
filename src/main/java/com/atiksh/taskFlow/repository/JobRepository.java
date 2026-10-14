
package com.atiksh.taskFlow.repository;

import com.atiksh.taskFlow.entity.Job;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Modifying;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface JobRepository extends JpaRepository<Job, UUID> {

    Page<Job> findByUserId(UUID userId, Pageable pageable);

    List<Job> findByUserIdAndStatus(
            UUID userId,
            Job.Status status
    );

    Optional<Job> findByUserIdAndIdempotencyKey(
            UUID userId,
            String idempotencyKey
    );

    @Query(value = """
            SELECT *
            FROM jobs
            WHERE status IN ('QUEUED', 'SCHEDULED', 'RETRY_WAIT')
              AND available_at <= CURRENT_TIMESTAMP
            ORDER BY created_at ASC
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Job> findNextJobToClaim();

    /**
     * Locks a job row so completion and recovery cannot change the same job
     * concurrently.
     */
    @Query(value = """
        SELECT *
        FROM jobs
        WHERE id = :jobId
        FOR UPDATE
        """, nativeQuery = true)
    Optional<Job> findByIdForUpdate(@Param("jobId") UUID jobId);

    /**
     * Uses the database clock to validate ownership and lease freshness.
     */
    @Query(value = """
        SELECT EXISTS (
            SELECT 1
            FROM jobs
            WHERE id = :jobId
              AND status = 'RUNNING'
              AND worker_id = :workerId
              AND attempt_count = :attemptNumber
              AND lease_expires_at > clock_timestamp()
        )
        """, nativeQuery = true)
    boolean hasValidLease(
            @Param("jobId") UUID jobId,
            @Param("workerId") String workerId,
            @Param("attemptNumber") int attemptNumber
    );

    @Modifying
    @Query(value = """
        UPDATE jobs
        SET lease_expires_at = clock_timestamp() + INTERVAL '30 seconds',
            updated_at = clock_timestamp()
        WHERE id = :jobId
          AND status = 'RUNNING'
          AND worker_id = :workerId
          AND attempt_count = :attemptNumber
          AND lease_expires_at > clock_timestamp()
        """, nativeQuery = true)
    int renewLease(
            @Param("jobId") UUID jobId,
            @Param("workerId") String workerId,
            @Param("attemptNumber") int attemptNumber
    );
    @Query(value = """
        SELECT CURRENT_TIMESTAMP + INTERVAL '30 seconds'
        """, nativeQuery = true)
    Instant newLeaseExpiry();

    @Query(value = """
        SELECT *
        FROM jobs
        WHERE status = 'RUNNING'
          AND lease_expires_at <= clock_timestamp()
        ORDER BY lease_expires_at ASC
        LIMIT 1
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<Job> findNextExpiredJobForRecovery();
}
