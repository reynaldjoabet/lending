-- Lending platform schema (PostgreSQL). Idempotent.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(254) NOT NULL UNIQUE,
    phone           VARCHAR(16)  NOT NULL,
    password_hash   VARCHAR(120) NOT NULL,
    full_name       VARCHAR(200) NOT NULL,
    role            VARCHAR(16)  NOT NULL DEFAULT 'applicant'
                    CHECK (role IN ('applicant','admin','back_office')),
    ssn_hash        CHAR(64) UNIQUE,
    ssn_last4       CHAR(4),
    date_of_birth   DATE,
    kyc_status      VARCHAR(16)  NOT NULL DEFAULT 'not_started'
                    CHECK (kyc_status IN ('not_started','pending','approved','rejected')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS businesses (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_user_id            UUID         NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    name                     VARCHAR(200) NOT NULL,
    ein                      CHAR(9)      NOT NULL UNIQUE,
    industry                 VARCHAR(64)  NOT NULL,
    founded_on               DATE         NOT NULL,
    annual_revenue_minor     BIGINT       NOT NULL CHECK (annual_revenue_minor > 0),
    monthly_revenue_minor    BIGINT       NOT NULL CHECK (monthly_revenue_minor > 0),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS businesses_owner_idx ON businesses(owner_user_id);

CREATE TABLE IF NOT EXISTS linked_accounts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id     UUID         NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    plaid_item_id   VARCHAR(64)  NOT NULL,
    routing_number  CHAR(9)      NOT NULL,
    last4           CHAR(4)      NOT NULL,
    holder_name     VARCHAR(200) NOT NULL,
    added_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS loan_applications (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_id             UUID         NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    requested_amount_minor  BIGINT       NOT NULL CHECK (requested_amount_minor > 0),
    currency                CHAR(3)      NOT NULL,
    requested_term_months   INT          NOT NULL CHECK (requested_term_months BETWEEN 1 AND 120),
    purpose                 TEXT         NOT NULL,
    status                  VARCHAR(24)  NOT NULL,
    score_id                UUID,
    priced_amount_minor     BIGINT,
    priced_term_months      INT,
    priced_apr_bps          INT,
    declined_reason         TEXT,
    submitted_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_at              TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS apps_business_idx ON loan_applications(business_id, submitted_at DESC);
CREATE INDEX IF NOT EXISTS apps_status_idx   ON loan_applications(status);

CREATE TABLE IF NOT EXISTS scoring_results (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id          UUID         NOT NULL REFERENCES loan_applications(id) ON DELETE CASCADE,
    model                   VARCHAR(64)  NOT NULL,
    score                   INT          NOT NULL CHECK (score BETWEEN 300 AND 850),
    pd_bps                  INT          NOT NULL CHECK (pd_bps BETWEEN 0 AND 10000),
    approve                 BOOLEAN      NOT NULL,
    recommended_amount      BIGINT       NOT NULL CHECK (recommended_amount > 0),
    recommended_term_months INT          NOT NULL CHECK (recommended_term_months BETWEEN 1 AND 120),
    recommended_apr_bps     INT          NOT NULL CHECK (recommended_apr_bps BETWEEN 0 AND 100000),
    inputs                  JSONB        NOT NULL,
    scored_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS loan_agreements (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id        UUID         NOT NULL REFERENCES loan_applications(id) ON DELETE CASCADE,
    docusign_envelope_id  CHAR(36)     NOT NULL UNIQUE,
    document_url          VARCHAR(512) NOT NULL,
    sent_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    signed_at             TIMESTAMPTZ,
    declined_at           TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS loans (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id           UUID         NOT NULL REFERENCES loan_applications(id) ON DELETE RESTRICT,
    business_id              UUID         NOT NULL REFERENCES businesses(id) ON DELETE RESTRICT,
    principal_minor          BIGINT       NOT NULL CHECK (principal_minor > 0),
    currency                 CHAR(3)      NOT NULL,
    term_months              INT          NOT NULL CHECK (term_months BETWEEN 1 AND 120),
    apr_bps                  INT          NOT NULL CHECK (apr_bps BETWEEN 0 AND 100000),
    status                   VARCHAR(16)  NOT NULL,
    outstanding_minor        BIGINT       NOT NULL DEFAULT 0 CHECK (outstanding_minor >= 0),
    disbursed_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    consecutive_delinquent   INT          NOT NULL DEFAULT 0 CHECK (consecutive_delinquent >= 0)
);

CREATE INDEX IF NOT EXISTS loans_business_idx ON loans(business_id);
CREATE INDEX IF NOT EXISTS loans_status_idx   ON loans(status);

CREATE TABLE IF NOT EXISTS repayments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_id         UUID         NOT NULL REFERENCES loans(id) ON DELETE CASCADE,
    sequence        INT          NOT NULL,
    due_on          DATE         NOT NULL,
    principal_minor BIGINT       NOT NULL CHECK (principal_minor > 0),
    interest_minor  BIGINT       NOT NULL CHECK (interest_minor > 0),
    total_minor     BIGINT       NOT NULL CHECK (total_minor > 0),
    status          VARCHAR(12)  NOT NULL CHECK (status IN ('scheduled','paid','failed','skipped')),
    captured_at     TIMESTAMPTZ,
    rail_ref        VARCHAR(128),
    UNIQUE (loan_id, sequence)
);

CREATE INDEX IF NOT EXISTS repayments_due_idx ON repayments(due_on) WHERE status = 'scheduled';

CREATE TABLE IF NOT EXISTS virtual_cards (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_id         UUID         NOT NULL REFERENCES loans(id) ON DELETE CASCADE,
    token           VARCHAR(128) NOT NULL,
    last4           CHAR(4)      NOT NULL,
    expiry          VARCHAR(5)   NOT NULL,
    status          VARCHAR(12)  NOT NULL CHECK (status IN ('issued','active','frozen','cancelled')),
    spend_limit     BIGINT       NOT NULL CHECK (spend_limit > 0),
    issued_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS bad_deals (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_id               UUID         NOT NULL UNIQUE REFERENCES loans(id) ON DELETE RESTRICT,
    stage                 VARCHAR(16)  NOT NULL CHECK (stage IN ('eligible','listed','sold','recovered')),
    discount_bps          INT,
    sale_proceeds_minor   BIGINT,
    buyer_ref             VARCHAR(128),
    flagged_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    listed_at             TIMESTAMPTZ,
    sold_at               TIMESTAMPTZ
);
