package com.atiksh.taskFlow;
import com.atiksh.taskFlow.service.JobClaimService;
import com.atiksh.taskFlow.entity.Job;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.util.Optional;
import java.io.UnsupportedEncodingException;
import org.springframework.test.context.ActiveProfiles;
import java.util.UUID;
import java.util.regex.Matcher;
import org.junit.jupiter.api.BeforeEach;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.atiksh.taskFlow.worker.JobWorker;
import com.atiksh.taskFlow.service.JobCompletionService;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.time.Instant;
import com.atiksh.taskFlow.service.JobHeartbeatService;
import java.util.Map;
import java.sql.Timestamp;
import com.atiksh.taskFlow.service.JobRecoveryService;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class JobIntegrationTests
{
    @Autowired
    private JobHeartbeatService jobHeartbeatService;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JobRecoveryService jobRecoveryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JobWorker jobWorker;

    @Autowired
    private JobCompletionService jobCompletionService;

    private UUID createTestUser() {
        UUID userId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id) VALUES (?)",
                userId
        );

        return userId;
    }
    private String getJobStatus(UUID jobId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM jobs WHERE id = ?",
                String.class,
                jobId
        );
    }
    private int countAttempts(UUID jobId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM job_attempts WHERE job_id = ?",
                Integer.class,
                jobId
        );
    }

    private MvcResult createJob(
            UUID userId,
            String recipient,
            String idempotencyKey
    ) throws Exception {
        String request = """
                {
                  "userId": "%s",
                  "jobType": "SEND_EMAIL",
                  "payload": {
                    "recipient": "%s",
                    "subject": "Integration test"
                  },
                  "maxAttempts": 3,
                  "idempotencyKey": "%s"
                }
                """.formatted(userId, recipient, idempotencyKey);

        return mockMvc.perform(
                        post("/api/v1/jobs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request)
                )
                .andReturn();
    }

    private UUID extractJobId(MvcResult result) throws UnsupportedEncodingException {
        String response = result.getResponse().getContentAsString();

        Matcher matcher = Pattern.compile(
                "\"id\"\\s*:\\s*\"([^\"]+)\""
        ).matcher(response);

        if (!matcher.find()) {
            throw new AssertionError(
                    "Job ID missing from response: " + response
            );
        }

        return UUID.fromString(matcher.group(1));
    }

    private int countJobs(UUID userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM jobs WHERE user_id = ?",
                Integer.class,
                userId
        );
    }
    private void makeJobEligible(UUID jobId) {
        jdbcTemplate.update(
                """
                UPDATE jobs
                SET available_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """,
                jobId
        );
    }
    private MvcResult createJobWithRecipient(
            UUID userId,
            String recipient,
            int maxAttempts,
            String idempotencyKey
    ) throws Exception {
        String request = """
            {
              "userId": "%s",
              "jobType": "SEND_EMAIL",
              "payload": {
                "recipient": %s,
                "subject": "Integration test"
              },
              "maxAttempts": %d,
              "idempotencyKey": "%s"
            }
            """.formatted(
                userId,
                recipient == null ? "null" : "\"" + recipient + "\"",
                maxAttempts,
                idempotencyKey
        );

        return mockMvc.perform(
                post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
        ).andReturn();
    }

    private void deleteTestUser(UUID userId) {
        jdbcTemplate.update(
                """
                DELETE FROM job_attempts
                WHERE job_id IN (
                    SELECT id FROM jobs WHERE user_id = ?
                )
                """,
                userId
        );

        jdbcTemplate.update(
                "DELETE FROM jobs WHERE user_id = ?",
                userId
        );

        jdbcTemplate.update(
                "DELETE FROM users WHERE id = ?",
                userId
        );
    }

    @BeforeEach
    void cleanTestDatabase() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE job_attempts, jobs, users CASCADE"
        );
    }
    @Test
    void shouldCreateJobAndPersistItInDatabase() throws Exception {
        UUID userId = createTestUser();

        try {
            MvcResult result = createJob(
                    userId,
                    "test@example.com",
                    "test-" + UUID.randomUUID()
            );

            assertEquals(201, result.getResponse().getStatus());
            assertEquals(1, countJobs(userId));
        } finally {
            deleteTestUser(userId);
        }
    }

    @Test
    void sameKeyAndSameRequestShouldReturnSameJob() throws Exception {
        UUID userId = createTestUser();
        String key = "idempotency-" + UUID.randomUUID();

        try {
            MvcResult first = createJob(
                    userId, "test@example.com", key
            );

            MvcResult second = createJob(
                    userId, "test@example.com", key
            );

            assertEquals(201, first.getResponse().getStatus());
            assertEquals(201, second.getResponse().getStatus());

            UUID firstJobId = extractJobId(first);
            UUID secondJobId = extractJobId(second);

            assertEquals(firstJobId, secondJobId);
            assertEquals(1, countJobs(userId));
        } finally {
            deleteTestUser(userId);
        }
    }

    @Test
    void sameKeyAndDifferentPayloadShouldReturnConflict()
            throws Exception {
        UUID userId = createTestUser();
        String key = "idempotency-" + UUID.randomUUID();

        try {
            MvcResult first = createJob(
                    userId, "first@example.com", key
            );

            MvcResult second = createJob(
                    userId, "different@example.com", key
            );

            assertEquals(201, first.getResponse().getStatus());
            assertEquals(409, second.getResponse().getStatus());
            assertEquals(1, countJobs(userId));
        } finally {
            deleteTestUser(userId);
        }
    }

    @Test
    void differentKeysShouldCreateDifferentJobs() throws Exception {
        UUID userId = createTestUser();

        try {
            MvcResult first = createJob(
                    userId,
                    "test@example.com",
                    "key-" + UUID.randomUUID()
            );

            MvcResult second = createJob(
                    userId,
                    "test@example.com",
                    "key-" + UUID.randomUUID()
            );

            assertEquals(201, first.getResponse().getStatus());
            assertEquals(201, second.getResponse().getStatus());

            UUID firstJobId = extractJobId(first);
            UUID secondJobId = extractJobId(second);

            assertNotEquals(firstJobId, secondJobId);
            assertEquals(2, countJobs(userId));
        } finally {
            deleteTestUser(userId);
        }
    }
    @Autowired
    private JobClaimService jobClaimService;

    @Test
    void claimingJobShouldMarkItRunningAndNotClaimItAgain()
            throws Exception {
        UUID userId = createTestUser();

        try {
            MvcResult result = createJob(
                    userId,
                    "claim-test@example.com",
                    "claim-" + UUID.randomUUID()
            );

            assertEquals(201, result.getResponse().getStatus());
            UUID jobId = extractJobId(result);
            makeJobEligible(jobId);

            Optional<Job> firstClaim = jobClaimService.claimNextJob();

            assertTrue(
                    firstClaim.isPresent(),
                    "The queue should contain an eligible job"
            );
            assertNotNull(firstClaim.get().getWorkerId());
            assertNotNull(firstClaim.get().getLeaseExpiresAt());
            assertTrue(firstClaim.get().getLeaseExpiresAt().isAfter(Instant.now()));

            Job claimedJob = firstClaim.get();

            assertEquals(Job.Status.RUNNING, claimedJob.getStatus());

            Optional<Job> secondClaim = jobClaimService.claimNextJob();

            assertTrue(
                    secondClaim.isEmpty()
                            || !secondClaim.get().getId()
                            .equals(claimedJob.getId()),
                    "A RUNNING job must not be claimed again"
            );
        } finally {
            deleteTestUser(userId);
        }
    }
    @Test
    void successfulJobShouldBeExecutedAndRecorded() throws Exception {

        UUID userId = createTestUser();
        String key = "worker-success-" + UUID.randomUUID();

        try {
            MvcResult result = createJob(
                    userId,
                    "test@example.com",
                    key
            );

            assertEquals(201, result.getResponse().getStatus());

            UUID jobId = extractJobId(result);
            makeJobEligible(jobId);

            // Process one eligible job.
            boolean processed = jobWorker.processOneJob();
            assertTrue(processed, "A queued job should be processed");

            assertEquals("SUCCEEDED", getJobStatus(jobId));
            assertEquals(1, countAttempts(jobId));

            String attemptStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM job_attempts WHERE job_id = ?",
                    String.class,
                    jobId
            );

            assertEquals("SUCCEEDED", attemptStatus);
        } finally {
            jdbcTemplate.update(
                    "DELETE FROM job_attempts WHERE job_id IN " +
                            "(SELECT id FROM jobs WHERE user_id = ?)",
                    userId
            );
            deleteTestUser(userId);
        }
    }
    @Test
    void failedJobShouldEnterRetryWaitAndRecordFailedAttempt()
            throws Exception {
        UUID userId = createTestUser();

        try {
            MvcResult result = createJobWithRecipient(
                    userId,
                    null,
                    3,
                    "retry-" + UUID.randomUUID()
            );

            assertEquals(201, result.getResponse().getStatus());

            UUID jobId = extractJobId(result);
            makeJobEligible(jobId);

            boolean processed = jobWorker.processOneJob();

            assertTrue(processed, "The eligible job should be claimed");

            assertEquals("RETRY_WAIT", getJobStatus(jobId));
            assertEquals(1, countAttempts(jobId));

            String attemptStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM job_attempts WHERE job_id = ?",
                    String.class,
                    jobId
            );

            assertEquals("FAILED", attemptStatus);
        } finally {
            deleteTestUser(userId);
        }
    }
    @Test
    void jobShouldBeDeadLetteredAfterAllAttemptsFail() throws Exception {
        UUID userId = createTestUser();

        try {
            MvcResult result = createJobWithRecipient(
                    userId,
                    null,
                    2,
                    "retry-sequence-" + UUID.randomUUID()
            );

            assertEquals(201, result.getResponse().getStatus());
            UUID jobId = extractJobId(result);
            makeJobEligible(jobId);

            // First attempt fails; retry should be scheduled.
            assertTrue(jobWorker.processOneJob());
            assertEquals("RETRY_WAIT", getJobStatus(jobId));
            assertEquals(1, countAttempts(jobId));

            // Make the retry eligible immediately in the test database.
            makeJobEligible(jobId);

            // Second attempt fails; the job should be dead-lettered.
            assertTrue(jobWorker.processOneJob());
            assertEquals("DEAD_LETTERED", getJobStatus(jobId));
            assertEquals(2, countAttempts(jobId));

            var attemptStatuses = jdbcTemplate.queryForList(
                    """
                    SELECT status
                    FROM job_attempts
                    WHERE job_id = ?
                    ORDER BY attempt_number
                    """,
                    String.class,
                    jobId
            );

            assertEquals(
                    java.util.List.of("FAILED", "FAILED"),
                    attemptStatuses
            );
        } finally {
            deleteTestUser(userId);
        }
    }
    @Test
    void heartbeatShouldRenewValidLease() throws Exception {
        UUID userId = createTestUser();

        try {
            MvcResult result = createJob(
                    userId,
                    "heartbeat@example.com",
                    "heartbeat-" + UUID.randomUUID()
            );
            UUID jobId = extractJobId(result);

            makeJobEligible(jobId);

            Job claimedJob = jobClaimService.claimNextJob().orElseThrow();
            assertEquals(jobId, claimedJob.getId());

            Instant oldExpiry = claimedJob.getLeaseExpiresAt();

            boolean renewed = jobHeartbeatService.renewLease(
                    claimedJob.getId(),
                    claimedJob.getWorkerId(),
                    claimedJob.getAttemptCount()
            );

            assertTrue(renewed);

            Instant newExpiry = jdbcTemplate.queryForObject(
                    "SELECT lease_expires_at FROM jobs WHERE id = ?",
                    (rs, rowNum) -> rs.getTimestamp(1).toInstant(),
                    jobId
            );

            assertTrue(newExpiry.isAfter(oldExpiry));
        } finally {
            deleteTestUser(userId);
        }
    }
    @Test
    void heartbeatShouldRejectExpiredLease() throws Exception {
        UUID userId = createTestUser();

        try {
            MvcResult result = createJob(
                    userId,
                    "heartbeat-expired@example.com",
                    "heartbeat-expired-" + UUID.randomUUID()
            );
            UUID jobId = extractJobId(result);

            makeJobEligible(jobId);

            Job claimedJob = jobClaimService.claimNextJob().orElseThrow();
            assertEquals(jobId, claimedJob.getId());

            jdbcTemplate.update(
                    """
                    UPDATE jobs
                    SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                    WHERE id = ?
                    """,
                    jobId
            );

            boolean renewed = jobHeartbeatService.renewLease(
                    claimedJob.getId(),
                    claimedJob.getWorkerId(),
                    claimedJob.getAttemptCount()
            );

            assertFalse(renewed);
        } finally {
            deleteTestUser(userId);
        }
    }

    @Test
    void expiredLeaseShouldMoveJobToRetryWait() throws Exception {
        UUID userId = createTestUser();
        try {
            MvcResult result = createJobWithRecipient(
                    userId,
                    "recovery-retry@example.com",
                    3,
                    "recovery-retry-" + UUID.randomUUID()
            );
            assertEquals(201, result.getResponse().getStatus());
            UUID jobId = extractJobId(result);
            makeJobEligible(jobId);

            Job claimedJob = jobClaimService.claimNextJob().orElseThrow();
            assertEquals(jobId, claimedJob.getId());

            jdbcTemplate.update("""
                    UPDATE jobs
                    SET lease_expires_at = clock_timestamp() - INTERVAL '1 second'
                    WHERE id = ?
                    """, jobId);

            assertTrue(jobRecoveryService.recoverNextExpiredJob());

            Map<String, Object> job = jdbcTemplate.queryForMap("""
                    SELECT status, attempt_count, last_error, worker_id,
                           lease_expires_at, available_at
                    FROM jobs WHERE id = ?
                    """, jobId);

            assertEquals("RETRY_WAIT", job.get("status"));
            assertEquals(1, ((Number) job.get("attempt_count")).intValue());
            assertEquals("Worker lease expired before job completion", job.get("last_error"));
            assertNull(job.get("worker_id"));
            assertNull(job.get("lease_expires_at"));

            Timestamp availableAt = (Timestamp) job.get("available_at");
            assertTrue(availableAt.toInstant().isAfter(Instant.now()),
                    "Retry should be scheduled in the future");

            Map<String, Object> attempt = jdbcTemplate.queryForMap("""
                    SELECT status, error_message
                    FROM job_attempts
                    WHERE job_id = ? AND attempt_number = 1
                    """, jobId);
            assertEquals("FAILED", attempt.get("status"));
            assertEquals("Worker lease expired before job completion", attempt.get("error_message"));
        } finally {
            deleteTestUser(userId);
        }
    }

    @Test
    void expiredLeaseOnFinalAttemptShouldDeadLetterJob() throws Exception {
        UUID userId = createTestUser();
        try {
            MvcResult result = createJobWithRecipient(
                    userId,
                    "recovery-final@example.com",
                    1,
                    "recovery-final-" + UUID.randomUUID()
            );
            assertEquals(201, result.getResponse().getStatus());
            UUID jobId = extractJobId(result);
            makeJobEligible(jobId);

            Job claimedJob = jobClaimService.claimNextJob().orElseThrow();
            assertEquals(jobId, claimedJob.getId());

            jdbcTemplate.update("""
                    UPDATE jobs
                    SET lease_expires_at = clock_timestamp() - INTERVAL '1 second'
                    WHERE id = ?
                    """, jobId);

            assertTrue(jobRecoveryService.recoverNextExpiredJob());

            Map<String, Object> job = jdbcTemplate.queryForMap("""
                    SELECT status, worker_id, lease_expires_at
                    FROM jobs WHERE id = ?
                    """, jobId);
            assertEquals("DEAD_LETTERED", job.get("status"));
            assertNull(job.get("worker_id"));
            assertNull(job.get("lease_expires_at"));

            Map<String, Object> attempt = jdbcTemplate.queryForMap("""
                    SELECT status
                    FROM job_attempts
                    WHERE job_id = ? AND attempt_number = 1
                    """, jobId);
            assertEquals("FAILED", attempt.get("status"));
        } finally {
            deleteTestUser(userId);
        }
    }

    @Test
    void recoveryShouldIgnoreUnexpiredLease() throws Exception {
        UUID userId = createTestUser();
        try {
            MvcResult result = createJobWithRecipient(
                    userId,
                    "recovery-active@example.com",
                    3,
                    "recovery-active-" + UUID.randomUUID()
            );
            assertEquals(201, result.getResponse().getStatus());
            UUID jobId = extractJobId(result);
            makeJobEligible(jobId);

            Job claimedJob = jobClaimService.claimNextJob().orElseThrow();
            assertEquals(jobId, claimedJob.getId());

            assertFalse(jobRecoveryService.recoverNextExpiredJob());
            assertEquals("RUNNING", getJobStatus(jobId));

            String attemptStatus = jdbcTemplate.queryForObject("""
                    SELECT status FROM job_attempts
                    WHERE job_id = ? AND attempt_number = 1
                    """, String.class, jobId);
            assertEquals("RUNNING", attemptStatus);
        } finally {
            deleteTestUser(userId);
        }
    }


    @Test
    void staleWorkerMustNotCompleteJobAfterRecoveryAndReclaim() throws Exception {
        UUID userId = createTestUser();

        try {
            MvcResult result = createJobWithRecipient(
                    userId,
                    "stale-worker@example.com",
                    3,
                    "stale-worker-" + UUID.randomUUID()
            );
            assertEquals(201, result.getResponse().getStatus());

            UUID jobId = extractJobId(result);
            makeJobEligible(jobId);

            // Attempt 1 is claimed by the original worker.
            Job firstClaim = jobClaimService.claimNextJob().orElseThrow();
            assertEquals(jobId, firstClaim.getId());
            assertEquals(1, firstClaim.getAttemptCount());
            String originalWorkerId = firstClaim.getWorkerId();

            // Simulate the original worker crashing and its lease expiring.
            jdbcTemplate.update("""
                    UPDATE jobs
                    SET lease_expires_at = clock_timestamp() - INTERVAL '1 second'
                    WHERE id = ?
                    """, jobId);

            assertTrue(jobRecoveryService.recoverNextExpiredJob());
            assertEquals("RETRY_WAIT", getJobStatus(jobId));

            // Make the retry eligible and claim attempt 2.
            makeJobEligible(jobId);
            Job secondClaim = jobClaimService.claimNextJob().orElseThrow();
            assertEquals(jobId, secondClaim.getId());
            assertEquals(2, secondClaim.getAttemptCount());
            assertEquals("RUNNING", getJobStatus(jobId));

            // The old attempt must not complete the newly claimed attempt.
            assertThrows(
                    IllegalStateException.class,
                    () -> jobCompletionService.markSucceeded(
                            jobId,
                            originalWorkerId,
                            firstClaim.getAttemptCount()
                    )
            );

            assertEquals("RUNNING", getJobStatus(jobId));

            Map<String, Object> currentJob = jdbcTemplate.queryForMap("""
                    SELECT attempt_count, worker_id
                    FROM jobs
                    WHERE id = ?
                    """, jobId);
            assertEquals(2, ((Number) currentJob.get("attempt_count")).intValue());
            assertEquals(secondClaim.getWorkerId(), currentJob.get("worker_id"));

            var attemptStatuses = jdbcTemplate.queryForList("""
                    SELECT status
                    FROM job_attempts
                    WHERE job_id = ?
                    ORDER BY attempt_number
                    """, String.class, jobId);
            assertEquals(java.util.List.of("FAILED", "RUNNING"), attemptStatuses);
        } finally {
            deleteTestUser(userId);
        }
    }

}
