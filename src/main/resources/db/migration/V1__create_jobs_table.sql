
-- V1: Create users, jobs, and job_attempts tables

CREATE TABLE users (
                       id UUID PRIMARY KEY,
                       created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE jobs (
                      id UUID PRIMARY KEY,

    -- Ownership
                      user_id UUID NOT NULL
                          REFERENCES users(id),

    -- Job definition
                      job_type VARCHAR(100) NOT NULL,
                      payload JSONB NOT NULL,

    -- Lifecycle state
                      status VARCHAR(30) NOT NULL DEFAULT 'QUEUED'
                          CHECK (status IN (
                                            'QUEUED',
                                            'SCHEDULED',
                                            'RUNNING',
                                            'SUCCEEDED',
                                            'FAILED',
                                            'RETRY_WAIT',
                                            'DEAD_LETTERED',
                                            'CANCELLED'
                              )),

    -- Scheduling
                      scheduled_at TIMESTAMPTZ,
                      available_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Retry policy
                      attempt_count INTEGER NOT NULL DEFAULT 0
                          CHECK (attempt_count >= 0),
                      max_attempts INTEGER NOT NULL DEFAULT 3
                          CHECK (max_attempts > 0),

    -- Idempotency
                      idempotency_key VARCHAR(255),

    -- Latest execution information
                      last_error TEXT,
                      result JSONB,

    -- Timestamps
                      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                      started_at TIMESTAMPTZ,
                      completed_at TIMESTAMPTZ
);

-- Prevent duplicate submissions for the same user and key.
-- Multiple jobs may have a NULL idempotency key.
CREATE UNIQUE INDEX uq_jobs_user_idempotency_key
    ON jobs (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Efficiently list a user's jobs, newest first.
CREATE INDEX idx_jobs_user_created
    ON jobs (user_id, created_at DESC);

-- Find jobs eligible for processing.
CREATE INDEX idx_jobs_ready
    ON jobs (available_at, created_at)
    WHERE status IN ('QUEUED', 'RETRY_WAIT');

-- Find scheduled jobs waiting for their execution time.
CREATE INDEX idx_jobs_scheduled
    ON jobs (scheduled_at)
    WHERE status = 'SCHEDULED';

-- Record each execution attempt separately.
CREATE TABLE job_attempts (
                              id UUID PRIMARY KEY,

                              job_id UUID NOT NULL
                                  REFERENCES jobs(id),

                              attempt_number INTEGER NOT NULL
                                  CHECK (attempt_number > 0),

                              status VARCHAR(30) NOT NULL
                                  CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),

                              started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                              completed_at TIMESTAMPTZ,
                              error_message TEXT,

    -- Each attempt number must be unique within a job.
                              CONSTRAINT uq_job_attempt_number
                                  UNIQUE (job_id, attempt_number),

    -- A completed attempt must have an end timestamp.
                              CONSTRAINT chk_job_attempt_completion
                                  CHECK (
                                      (status = 'RUNNING' AND completed_at IS NULL)
                                          OR
                                      (status IN ('SUCCEEDED', 'FAILED')
                                          AND completed_at IS NOT NULL)
                                      )
);

-- Retrieve attempts for a job in chronological order.
CREATE INDEX idx_job_attempts_job_id
    ON job_attempts (job_id, attempt_number);
