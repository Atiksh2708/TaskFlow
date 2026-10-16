
package com.atiksh.taskFlow.ratelimit;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class RateLimitServiceTest {

    @Autowired
    private RateLimitService rateLimitService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private final String clientId = "test-client-rate-limit";

    @BeforeEach
    void clearBucket() {
        redisTemplate.delete("rate-limit:" + clientId);
    }

    @Test
    void allowsTenRequestsAndRejectsEleventh() {
        for (int i = 0; i < 10; i++) {
            RateLimitResult result = rateLimitService.checkLimit(clientId);
            assertTrue(result.allowed(), "Request " + (i + 1) + " should be allowed");
        }

        RateLimitResult result = rateLimitService.checkLimit(clientId);

        assertFalse(result.allowed());
        assertEquals(0, result.remainingTokens());
        assertTrue(result.retryAfterSeconds() > 0);
    }
    @Test
    void clientsHaveIndependentRateLimits() {
        String clientA = "test-client-isolation-A";
        String clientB = "test-client-isolation-B";

        redisTemplate.delete("rate-limit:" + clientA);
        redisTemplate.delete("rate-limit:" + clientB);

        for (int i = 0; i < 10; i++) {
            assertTrue(rateLimitService.checkLimit(clientA).allowed());
        }

        assertFalse(rateLimitService.checkLimit(clientA).allowed());

        // Exhausting client A must not affect client B.
        RateLimitResult clientBResult =
                rateLimitService.checkLimit(clientB);

        assertTrue(clientBResult.allowed());
        assertEquals(9, clientBResult.remainingTokens());
    }

    @Test
    void rejectedRequestIncludesPositiveRetryDelay() {
        String clientId = "test-client-retry-delay";
        redisTemplate.delete("rate-limit:" + clientId);

        for (int i = 0; i < 10; i++) {
            rateLimitService.checkLimit(clientId);
        }

        RateLimitResult result = rateLimitService.checkLimit(clientId);

        assertFalse(result.allowed());
        assertTrue(result.retryAfterSeconds() >= 1);
        assertEquals(0, result.remainingTokens());
    }
    @Test
    void tokensRefillOverTime() throws InterruptedException {
        String clientId = "test-client-refill";
        redisTemplate.delete("rate-limit:" + clientId);

        // Exhaust the bucket.
        for (int i = 0; i < 10; i++) {
            assertTrue(rateLimitService.checkLimit(clientId).allowed());
        }

        assertFalse(rateLimitService.checkLimit(clientId).allowed());

        // Wait long enough for approximately one token to refill.
        Thread.sleep(6500);

        RateLimitResult result = rateLimitService.checkLimit(clientId);

        assertTrue(result.allowed());
        assertTrue(result.remainingTokens() >= 0);
    }
}
