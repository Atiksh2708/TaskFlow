package com.atiksh.taskFlow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "taskflow.worker.enabled=true",
        "taskflow.worker.poll-interval-ms=1000",
        "taskflow.worker.recovery-interval-ms=100"
})
@AutoConfigureMockMvc
class AutomaticJobRecoveryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTestDatabase() {
        jdbcTemplate.execute("TRUNCATE TABLE job_attempts, jobs, users CASCADE");
    }

    @Test
    void recoverySchedulerShouldRecoverExpiredLeaseAutomatically() throws Exception {
        UUID userId = UUID.randomUUID();

        jdbcTemplate.update("INSERT INTO users (id) VALUES (?)", userId);

        try {
            String idempotencyKey = "auto-recovery-" + UUID.randomUUID();
            String request = """
                    {
                      "userId": "%s",
                      "jobType": "SEND_EMAIL",
                      "payload": {
                        "recipient": "automatic-recovery@example.com",
                        "subject": "Automatic recovery test"
                      },
                      "maxAttempts": 3,
                      "idempotencyKey": "%s"
                    }
                    """.formatted(userId, idempotencyKey);

            MvcResult result = mockMvc.perform(post("/api/v1/jobs")
                            .contentType(APPLICATION_JSON)
                            .content(request))
                    .andReturn();

            assertEquals(201, result.getResponse().getStatus(),
                    result.getResponse().getContentAsString());

            Matcher matcher = Pattern.compile(
                    "\"id\"\\s*:\\s*\"([^\"]+)\""
            ).matcher(result.getResponse().getContentAsString());

            assertTrue(matcher.find(),
                    "Expected job response to contain an id: "
                            + result.getResponse().getContentAsString());

            UUID jobId = UUID.fromString(matcher.group(1));
            UUID oldWorkerId = UUID.randomUUID();
            String workerId = "crashed-worker-" + oldWorkerId;

            // Simulate a worker that claimed the job and then disappeared.
            jdbcTemplate.update("""
                    UPDATE jobs
                    SET status = 'RUNNING',
                        attempt_count = 1,
                        worker_id = ?,
                        started_at = clock_timestamp() - INTERVAL '1 minute',
                        lease_expires_at = clock_timestamp() - INTERVAL '1 second',
                        available_at = clock_timestamp()
                    WHERE id = ?
                    """, workerId, jobId);

            jdbcTemplate.update("""
                    INSERT INTO job_attempts
                        (id, job_id, attempt_number, status, started_at)
                    VALUES (?, ?, 1, 'RUNNING',
                            clock_timestamp() - INTERVAL '1 minute')
                    """, UUID.randomUUID(), jobId);

            long deadline = System.nanoTime() + 10_000_000_000L;
            String status = "UNKNOWN";

            while (System.nanoTime() < deadline) {
                status = jdbcTemplate.queryForObject(
                        "SELECT status FROM jobs WHERE id = ?",
                        String.class, jobId);

                if ("RETRY_WAIT".equals(status)
                        || "DEAD_LETTERED".equals(status)) {
                    break;
                }

                Thread.sleep(25);
            }

            assertEquals("RETRY_WAIT", status,
                    "Recovery scheduler did not move the expired job to RETRY_WAIT");

            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM job_attempts "
                            + "WHERE job_id = ? AND attempt_number = 1 "
                            + "AND status = 'FAILED'",
                    Integer.class, jobId));

            assertNull(jdbcTemplate.queryForObject(
                    "SELECT worker_id FROM jobs WHERE id = ?",
                    String.class, jobId));

            assertNull(jdbcTemplate.queryForObject(
                    "SELECT lease_expires_at FROM jobs WHERE id = ?",
                    java.time.OffsetDateTime.class, jobId));

            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM jobs WHERE id = ? "
                            + "AND last_error = 'Worker lease expired before job completion'",
                    Integer.class, jobId));
        } finally {
            jdbcTemplate.update(
                    "DELETE FROM job_attempts WHERE job_id IN "
                            + "(SELECT id FROM jobs WHERE user_id = ?)", userId);
            jdbcTemplate.update("DELETE FROM jobs WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }
}
