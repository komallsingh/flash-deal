-- Restrict idempotency keys to the assignment's 1-64 character format.
ALTER TABLE idempotency_keys
ALTER COLUMN idempotency_key TYPE VARCHAR(64);

ALTER TABLE idempotency_keys
ADD CONSTRAINT chk_idempotency_key_format
CHECK (idempotency_key ~ '^[A-Za-z0-9_-]{1,64}$');

-- Track ownership and recovery of in-progress requests.
ALTER TABLE idempotency_keys
ADD COLUMN lease_expires_at TIMESTAMPTZ
    NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '60 seconds');

ALTER TABLE idempotency_keys
ADD COLUMN lease_token UUID
    NOT NULL DEFAULT gen_random_uuid();

-- Track when the original response was completed.
ALTER TABLE idempotency_keys
ADD COLUMN completed_at TIMESTAMPTZ;

-- A row must either be in progress or have a complete stored response.
ALTER TABLE idempotency_keys
ADD CONSTRAINT chk_idempotency_completion
CHECK (
    (
        response_status IS NULL
        AND response_body IS NULL
        AND completed_at IS NULL
    )
    OR
    (
        response_status IS NOT NULL
        AND response_body IS NOT NULL
        AND completed_at IS NOT NULL
    )
);