CREATE TABLE deals (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL,

    title VARCHAR(100) NOT NULL,
    price_paise INTEGER NOT NULL,

    stock_total INTEGER NOT NULL,
    stock_available INTEGER NOT NULL,

    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_deal_title
        CHECK (LENGTH(TRIM(title)) BETWEEN 1 AND 100),

    CONSTRAINT chk_deal_price
        CHECK (price_paise BETWEEN 1 AND 1000000),

    CONSTRAINT chk_deal_stock_total
        CHECK (stock_total BETWEEN 1 AND 10000),

    CONSTRAINT chk_deal_stock_available
        CHECK (
            stock_available >= 0
            AND stock_available <= stock_total
        ),

    CONSTRAINT chk_deal_dates
        CHECK (starts_at < ends_at)
);


CREATE TABLE claims (
    id UUID PRIMARY KEY,

    deal_id UUID NOT NULL REFERENCES deals(id),
    customer_id UUID NOT NULL,

    quantity INTEGER NOT NULL,
    amount_paise INTEGER NOT NULL,

    status VARCHAR(20) NOT NULL,

    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_claim_quantity
        CHECK (quantity BETWEEN 1 AND 3),

    CONSTRAINT chk_claim_amount
        CHECK (amount_paise > 0),

    CONSTRAINT chk_claim_status
        CHECK (
            status IN (
                'RESERVED',
                'PAID',
                'EXPIRED',
                'NEEDS_REVIEW',
                'REFUND_PENDING'
            )
        )
);


CREATE INDEX idx_claims_deal_id
    ON claims(deal_id);

CREATE INDEX idx_claims_customer_id
    ON claims(customer_id);

CREATE INDEX idx_claims_expiry
    ON claims (expires_at)
    WHERE status = 'RESERVED';


CREATE UNIQUE INDEX uq_active_claim_per_customer_deal
    ON claims(customer_id, deal_id)
    WHERE status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW');