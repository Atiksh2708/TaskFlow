package com.atiksh.taskFlow.ratelimit;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

@Service
public class RateLimitService {

    private static final int CAPACITY = 10;
    private static final double REFILL_PER_SECOND = 10.0 / 60.0;

    private static final String RATE_LIMIT_SCRIPT = """
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local refillRate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])

            local bucket = redis.call('HMGET', key, 'tokens', 'lastRefill')
            local tokens = tonumber(bucket[1])
            local lastRefill = tonumber(bucket[2])

            if tokens == nil then
                tokens = capacity
                lastRefill = now
            end

            local elapsed = math.max(0, now - lastRefill) / 1000
            tokens = math.min(capacity, tokens + elapsed * refillRate)

            local allowed = 0
            local retryAfter = 0

            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            else
                retryAfter = math.ceil((1 - tokens) / refillRate)
            end

            redis.call('HSET', key,
                'tokens', tostring(tokens),
                'lastRefill', tostring(now))

            redis.call('EXPIRE', key, 120)

            return {allowed, math.floor(tokens), retryAfter}
            """;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> script;

    public RateLimitService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;

        this.script = new DefaultRedisScript<>();
        this.script.setScriptText(RATE_LIMIT_SCRIPT);
        this.script.setResultType(List.class);
    }

    public RateLimitResult checkLimit(String clientId) {
        long now = System.currentTimeMillis();

        List<?> result = redisTemplate.execute(
                script,
                List.of("rate-limit:" + clientId),
                String.valueOf(CAPACITY),
                String.valueOf(REFILL_PER_SECOND),
                String.valueOf(now)
        );

        if (result == null || result.size() != 3) {
            throw new IllegalStateException(
                    "Unexpected response from Redis rate limiter");
        }

        long allowed = ((Number) result.get(0)).longValue();
        long remaining = ((Number) result.get(1)).longValue();
        long retryAfter = ((Number) result.get(2)).longValue();

        return new RateLimitResult(
                allowed == 1,
                remaining,
                retryAfter
        );
    }
}
