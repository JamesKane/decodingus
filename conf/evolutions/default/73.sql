# --- !Ups

CREATE SCHEMA IF NOT EXISTS billing;

CREATE TABLE billing.patron_subscription (
    id                      SERIAL PRIMARY KEY,
    user_id                 UUID NOT NULL,
    patron_tier             VARCHAR(30) NOT NULL CHECK (patron_tier IN ('SUPPORTER', 'CONTRIBUTOR', 'SUSTAINER', 'FOUNDING_PATRON')),
    status                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CANCELLED', 'PAST_DUE', 'EXPIRED')),
    payment_provider        VARCHAR(20) NOT NULL CHECK (payment_provider IN ('STRIPE', 'PAYPAL')),
    provider_subscription_id VARCHAR(255),
    provider_customer_id    VARCHAR(255),
    amount_cents            INTEGER NOT NULL,
    currency                VARCHAR(3) NOT NULL DEFAULT 'USD',
    billing_interval        VARCHAR(10) NOT NULL CHECK (billing_interval IN ('MONTHLY', 'YEARLY')),
    current_period_start    TIMESTAMPTZ,
    current_period_end      TIMESTAMPTZ,
    cancelled_at            TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_patron_sub_user ON billing.patron_subscription (user_id);
CREATE INDEX idx_patron_sub_status ON billing.patron_subscription (status);
CREATE INDEX idx_patron_sub_provider ON billing.patron_subscription (payment_provider, provider_subscription_id);

# --- !Downs

DROP TABLE IF EXISTS billing.patron_subscription;
DROP SCHEMA IF EXISTS billing;
