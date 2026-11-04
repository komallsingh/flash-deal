CREATE TABLE payment_events (
    event_id VARCHAR(255) PRIMARY KEY,
    claim_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX idx_payment_events_claim_id
    ON payment_events(claim_id);