-- Lending platform schema. Idempotent: safe to re-run against an existing database.
--
-- Requires PostgreSQL 18+ (uuidv7()); tuned for PostgreSQL 19.
-- Run it as one transaction so a failure leaves nothing half-applied:
--   psql -v ON_ERROR_STOP=1 --single-transaction -d lending -f src/main/resources/db/schema.sql
--
-- Column types must stay in step with the skunk codecs in lending.db.Codecs,
-- which check them when a statement is prepared.

DO $$
BEGIN
    IF current_setting('server_version_num')::int < 180000 THEN
        RAISE EXCEPTION 'schema.sql needs PostgreSQL 18 or newer (found %)', version();
    END IF;
END
$$;

-- On a re-run, the DROP INDEX and ALTER TABLE statements below take table locks.
-- Fail fast rather than queue behind application traffic.
SET lock_timeout = '5s';

-- Primary keys default to uuidv7() (built in since PostgreSQL 18, so the script no
-- longer creates pgcrypto; an existing install is left alone). The app supplies its
-- own UUIDv7 ids (lending.domain.Uuids), so the default only matters for rows
-- inserted by hand. Either way, new keys land at the
-- right-hand edge of the primary-key B-tree instead of on random pages.

CREATE TABLE IF NOT EXISTS users (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    email           VARCHAR(254) NOT NULL UNIQUE,
    phone           VARCHAR(16)  NOT NULL,
    password_hash   VARCHAR(120) NOT NULL,
    full_name       VARCHAR(200) NOT NULL,
    role            VARCHAR(16)  NOT NULL DEFAULT 'applicant'
                    CHECK (role IN ('applicant','admin','back_office')),
    ssn_hash        CHAR(64) UNIQUE,
    ssn_last4       CHAR(4)      CHECK (ssn_last4 ~ '^[0-9]{4}$'),
    date_of_birth   DATE,
    kyc_status      VARCHAR(16)  NOT NULL DEFAULT 'not_started'
                    CHECK (kyc_status IN ('not_started','pending','approved','rejected')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS businesses (
    id                       UUID PRIMARY KEY DEFAULT uuidv7(),
    owner_user_id            UUID         NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    name                     VARCHAR(200) NOT NULL,
    ein                      CHAR(9)      NOT NULL UNIQUE CHECK (ein ~ '^[0-9]{9}$'),
    industry                 VARCHAR(64)  NOT NULL,
    founded_on               DATE         NOT NULL,
    annual_revenue_minor     BIGINT       NOT NULL CHECK (annual_revenue_minor > 0),
    monthly_revenue_minor    BIGINT       NOT NULL CHECK (monthly_revenue_minor > 0),
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS linked_accounts (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    business_id     UUID         NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    plaid_item_id   VARCHAR(64)  NOT NULL,
    routing_number  CHAR(9)      NOT NULL CHECK (routing_number ~ '^[0-9]{9}$'),
    last4           CHAR(4)      NOT NULL CHECK (last4 ~ '^[0-9]{4}$'),
    holder_name     VARCHAR(200) NOT NULL,
    added_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- fillfactor 90 leaves room on each page so the status and decision updates can be
-- HOT updates (no index changes), which keeps bloat and WAL down.
CREATE TABLE IF NOT EXISTS loan_applications (
    id                      UUID PRIMARY KEY DEFAULT uuidv7(),
    business_id             UUID         NOT NULL REFERENCES businesses(id) ON DELETE CASCADE,
    requested_amount_minor  BIGINT       NOT NULL CHECK (requested_amount_minor > 0),
    currency                CHAR(3)      NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    requested_term_months   INT          NOT NULL CHECK (requested_term_months BETWEEN 1 AND 120),
    purpose                 TEXT         NOT NULL,
    status                  VARCHAR(24)  NOT NULL
                            CHECK (status IN ('submitted','underwriting','approved','declined',
                                              'withdrawn','agreement_sent','agreement_signed',
                                              'disbursed')),
    score_id                UUID,
    priced_amount_minor     BIGINT       CHECK (priced_amount_minor > 0),
    priced_term_months      INT          CHECK (priced_term_months BETWEEN 1 AND 120),
    priced_apr_bps          INT          CHECK (priced_apr_bps BETWEEN 0 AND 100000),
    declined_reason         TEXT,
    submitted_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_at              TIMESTAMPTZ,
    -- Pricing is written as a unit by Applications.approve: all three or none.
    CONSTRAINT loan_applications_pricing_complete CHECK (
        num_nulls(priced_amount_minor, priced_term_months, priced_apr_bps) IN (0, 3)
    )
) WITH (fillfactor = 90);

CREATE TABLE IF NOT EXISTS scoring_results (
    id                      UUID PRIMARY KEY DEFAULT uuidv7(),
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
    id                    UUID PRIMARY KEY DEFAULT uuidv7(),
    application_id        UUID         NOT NULL REFERENCES loan_applications(id) ON DELETE CASCADE,
    docusign_envelope_id  CHAR(36)     NOT NULL UNIQUE,
    document_url          VARCHAR(512) NOT NULL,
    sent_at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    signed_at             TIMESTAMPTZ,
    declined_at           TIMESTAMPTZ,
    CONSTRAINT loan_agreements_single_outcome CHECK (signed_at IS NULL OR declined_at IS NULL)
);

-- The hottest table for updates (outstanding balance, delinquency counter, status).
-- None of those columns is indexed, so with spare page space they stay HOT updates.
CREATE TABLE IF NOT EXISTS loans (
    id                       UUID PRIMARY KEY DEFAULT uuidv7(),
    application_id           UUID         NOT NULL REFERENCES loan_applications(id) ON DELETE RESTRICT,
    business_id              UUID         NOT NULL REFERENCES businesses(id) ON DELETE RESTRICT,
    principal_minor          BIGINT       NOT NULL CHECK (principal_minor > 0),
    currency                 CHAR(3)      NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    term_months              INT          NOT NULL CHECK (term_months BETWEEN 1 AND 120),
    apr_bps                  INT          NOT NULL CHECK (apr_bps BETWEEN 0 AND 100000),
    status                   VARCHAR(16)  NOT NULL
                             CHECK (status IN ('active','delinquent','paid_off','charged_off','sold')),
    outstanding_minor        BIGINT       NOT NULL DEFAULT 0 CHECK (outstanding_minor >= 0),
    disbursed_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    consecutive_delinquent   INT          NOT NULL DEFAULT 0 CHECK (consecutive_delinquent >= 0)
) WITH (fillfactor = 85);

CREATE TABLE IF NOT EXISTS repayments (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    loan_id         UUID         NOT NULL REFERENCES loans(id) ON DELETE CASCADE,
    sequence        INT          NOT NULL CHECK (sequence >= 1),
    due_on          DATE         NOT NULL,
    principal_minor BIGINT       NOT NULL CHECK (principal_minor > 0),
    interest_minor  BIGINT       NOT NULL CHECK (interest_minor > 0),
    total_minor     BIGINT       NOT NULL CHECK (total_minor > 0),
    status          VARCHAR(12)  NOT NULL CHECK (status IN ('scheduled','paid','failed','skipped')),
    captured_at     TIMESTAMPTZ,
    rail_ref        VARCHAR(128),
    -- Also serves every "repayments for loan X" lookup and the FK cascade from loans.
    UNIQUE (loan_id, sequence),
    CONSTRAINT repayments_total_adds_up CHECK (total_minor = principal_minor + interest_minor)
);

CREATE TABLE IF NOT EXISTS virtual_cards (
    id              UUID PRIMARY KEY DEFAULT uuidv7(),
    loan_id         UUID         NOT NULL REFERENCES loans(id) ON DELETE CASCADE,
    token           VARCHAR(128) NOT NULL,
    last4           CHAR(4)      NOT NULL CHECK (last4 ~ '^[0-9]{4}$'),
    expiry          VARCHAR(5)   NOT NULL CHECK (expiry ~ '^(0[1-9]|1[0-2])/[0-9]{2}$'),
    status          VARCHAR(12)  NOT NULL CHECK (status IN ('issued','active','frozen','cancelled')),
    spend_limit     BIGINT       NOT NULL CHECK (spend_limit > 0),
    issued_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS bad_deals (
    id                    UUID PRIMARY KEY DEFAULT uuidv7(),
    loan_id               UUID         NOT NULL UNIQUE REFERENCES loans(id) ON DELETE RESTRICT,
    stage                 VARCHAR(16)  NOT NULL CHECK (stage IN ('eligible','listed','sold','recovered')),
    discount_bps          INT          CHECK (discount_bps BETWEEN 0 AND 10000),
    sale_proceeds_minor   BIGINT       CHECK (sale_proceeds_minor > 0),
    buyer_ref             VARCHAR(128),
    flagged_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    listed_at             TIMESTAMPTZ,
    sold_at               TIMESTAMPTZ
);

-- ---------------------------------------------------------------------------
-- Indexes. Each one serves a query in lending.db.* or a foreign key. An
-- unindexed FK column makes ON DELETE CASCADE/RESTRICT scan the whole child table.
-- ---------------------------------------------------------------------------

-- Businesses.listForOwner; FK users -> businesses
CREATE INDEX IF NOT EXISTS businesses_owner_idx ON businesses (owner_user_id);

-- Businesses.linkedAccountsFor; FK businesses -> linked_accounts
CREATE INDEX IF NOT EXISTS linked_accounts_business_idx ON linked_accounts (business_id);

-- Applications.listForBusiness (WHERE business_id = ? ORDER BY submitted_at DESC)
CREATE INDEX IF NOT EXISTS apps_business_idx ON loan_applications (business_id, submitted_at DESC);

-- FK loan_applications -> scoring_results (cascade on delete)
CREATE INDEX IF NOT EXISTS scoring_results_application_idx ON scoring_results (application_id);

-- Agreements.findByApplication; FK loan_applications -> loan_agreements
CREATE INDEX IF NOT EXISTS loan_agreements_application_idx ON loan_agreements (application_id);

-- Loans.listForBusiness; FK businesses -> loans
CREATE INDEX IF NOT EXISTS loans_business_idx ON loans (business_id);

-- FK loan_applications -> loans (ON DELETE RESTRICT check)
CREATE INDEX IF NOT EXISTS loans_application_idx ON loans (application_id);

-- Loans.overdueAsOf (WHERE status = 'scheduled' AND due_on < ?).
-- Partial: paid rows, which become the bulk of the table, are never indexed.
CREATE INDEX IF NOT EXISTS repayments_due_idx ON repayments (due_on) WHERE status = 'scheduled';

-- Loans.nextDue (WHERE loan_id = ? AND status = 'scheduled' ORDER BY due_on LIMIT 1)
CREATE INDEX IF NOT EXISTS repayments_next_due_idx
    ON repayments (loan_id, due_on) WHERE status = 'scheduled';

-- Cards.findForLoan (WHERE loan_id = ? ORDER BY issued_at DESC LIMIT 1); FK loans -> virtual_cards
CREATE INDEX IF NOT EXISTS virtual_cards_loan_idx ON virtual_cards (loan_id, issued_at DESC);

-- BadDeals.listByStage (WHERE stage = ? ORDER BY flagged_at)
CREATE INDEX IF NOT EXISTS bad_deals_stage_idx ON bad_deals (stage, flagged_at);

-- No query filters on these, and because status is indexed, every status
-- change was a non-HOT update that also rewrote all the table's other indexes.
DROP INDEX IF EXISTS apps_status_idx;
DROP INDEX IF EXISTS loans_status_idx;

-- ---------------------------------------------------------------------------
-- Storage settings. These are repeated here as ALTER statements so a re-run also
-- applies them to tables created by an older version of this script. They only
-- affect pages written from now on. To rewrite existing pages without blocking
-- reads or writes, run REPACK (CONCURRENTLY) on PostgreSQL 19+.
-- ---------------------------------------------------------------------------

ALTER TABLE loan_applications SET (fillfactor = 90);
ALTER TABLE loans             SET (fillfactor = 85);

-- The defaults (20% dead rows, 10% for analyze) let a large, update-heavy table
-- build up a lot of bloat and keep stale planner stats on due_on and status.
ALTER TABLE repayments SET (
    autovacuum_vacuum_scale_factor  = 0.05,
    autovacuum_analyze_scale_factor = 0.02
);
ALTER TABLE loans SET (
    autovacuum_vacuum_scale_factor  = 0.05,
    autovacuum_analyze_scale_factor = 0.02
);
