package com.atiksh.taskFlow.service;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyConstraintDetector {

    private static final String CONSTRAINT_NAME =
            "uq_jobs_user_idempotency_key";

    public boolean isIdempotencyConflict(Throwable error) {
        Throwable current = error;

        while (current != null) {
            if (current instanceof ConstraintViolationException violation) {
                return CONSTRAINT_NAME.equals(violation.getConstraintName());
            }

            current = current.getCause();
        }

        return false;
    }
}
