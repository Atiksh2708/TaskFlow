ALTER TABLE jobs
    ADD COLUMN worker_id VARCHAR(100),
    ADD COLUMN lease_expires_at TIMESTAMPTZ;

CREATE INDEX idx_jobs_expired_leases
    ON jobs (lease_expires_at)
    WHERE status = 'RUNNING';