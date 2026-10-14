package com.atiksh.taskFlow.service;

import org.springframework.stereotype.Component;

@Component
public class RetryPolicy {

    private static final long BASE_RETRY_DELAY_SECONDS = 5;
    private static final long MAX_RETRY_DELAY_SECONDS = 300;

    public long calculateRetryDelay(int attemptCount) {
        int exponent = Math.min(Math.max(attemptCount - 1, 0), 10);

        return Math.min(
                BASE_RETRY_DELAY_SECONDS * (1L << exponent),
                MAX_RETRY_DELAY_SECONDS
        );
    }
}