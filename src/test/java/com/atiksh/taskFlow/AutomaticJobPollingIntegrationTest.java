package com.atiksh.taskFlow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "taskflow.worker.enabled=true",
        "taskflow.worker.poll-interval-ms=100",
        "taskflow.worker.recovery-interval-ms=1000"
})
@AutoConfigureMockMvc
class AutomaticJobPollingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTestDatabase() {
        jdbcTemplate.execute(
                "TRUNCATE TABLE job_attempts, jobs, users CASCADE"
        );
    }

    @Test
    void schedulerShouldProcessEligibleJobWithoutManualWorkerCall() throws Exception {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id) VALUES (?)", userId);

        try {
            String request = """
                    {
                      "userId": "%s",
                      "jobType": "SEND_EMAIL",
                      "payload": {
                        "recipient": "automatic-polling@example.com",
                        "subject": "Automatic polling test"
                      },
                      "maxAttempts": 3,
                      "idempotencyKey": "auto-poll-%s"
                    }
                    """.formatted(userId, UUID.randomUUID());

            MvcResult result = mockMvc.perform(
                    post("/api/v1/jobs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(request)
            ).andReturn();

            assertEquals(201, result.getResponse().getStatus());

            Matcher matcher = Pattern.compile(
                    "\"id\"\\s*:\\s*\"([^\"]+)\""
            ).matcher(result.getResponse().getContentAsString());

            assertTrue(matcher.find(), "Creation response should contain the job ID");
            UUID jobId = UUID.fromString(matcher.group(1));

            jdbcTemplate.update("""
                    UPDATE jobs
                    SET available_at = clock_timestamp() - INTERVAL '1 second'
                    WHERE id = ?
                    """, jobId);

            long deadline = System.nanoTime() + 10_000_000_000L;
            String status = "UNKNOWN";

            while (System.nanoTime() < deadline) {
                status = jdbcTemplate.queryForObject(
                        "SELECT status FROM jobs WHERE id = ?",
                        String.class,
                        jobId
                );

                if ("SUCCEEDED".equals(status) || "FAILED".equals(status)
                        || "DEAD_LETTERED".equals(status)) {
                    break;
                }

                Thread.sleep(50);
            }

            assertEquals(
                    "SUCCEEDED",
                    status,
                    "The scheduled worker should process the job without a manual call"
            );

            Integer attempts = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM job_attempts WHERE job_id = ?",
                    Integer.class,
                    jobId
            );
            assertEquals(1, attempts);

            String attemptStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM job_attempts WHERE job_id = ? AND attempt_number = 1",
                    String.class,
                    jobId
            );
            assertEquals("SUCCEEDED", attemptStatus);
        } finally {
            jdbcTemplate.update("""
                    DELETE FROM job_attempts
                    WHERE job_id IN (SELECT id FROM jobs WHERE user_id = ?)
                    """, userId);
            jdbcTemplate.update("DELETE FROM jobs WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }
}
