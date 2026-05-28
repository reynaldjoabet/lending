# Digital Lending Platform
A Scala 3 backend for the SMB digital-lending platform from the brief.
Covers signup + KYC, business onboarding, Plaid-linked bank accounts, applications routed through an AI scoring engine, DocuSign-gated agreements,
Mbanq-issued virtual cards on disbursement, and an automated bad-deal
disposal workflow.

## Stack

- Scala 3.3.6 — **braces-only** (`-no-indent`)
- Iron + iron-skunk + iron-circe
- Skunk on Postgres
- http4s (Ember) + Circe + Cats Effect 3
- bcrypt, log4cats / logback

## Distinctive bits

### 1. The scoring-engine seam

The brief calls for an **AI-based ensemble**. The platform's contract with
it is one trait:

```scala
trait ScoringEngine[F[_]] {
  def score(applicationId: ApplicationId, inputs: ScoringInputs): F[ScoringResult]
}
```

`ScoringResult` carries `model: String` ("ensemble_v3.1") so every persisted
decision is reproducible against the exact model version that made it. Swap the sandbox impl for a real model server (REST/gRPC) — no other code changes.

### 2. Underwriting policy lives outside the model

`UnderwritingService.applyPolicy` runs on top of the engine's recommendation to cap the offer against what the applicant requested. 

### 3. DocuSign-gated disbursement

The application reaches `Approved` from underwriting, but no money moves
until DocuSign reports a signed envelope. The webhook handler on
`/webhooks/docusign` → `LoanLifecycle.handleSigned` is the only path that
runs `disburse()`, which then atomically:

1. Creates the `Loan` row + the amortised repayment schedule.
2. Calls `mbanq.disburse` to push funds to the linked account.
3. Issues a virtual card via Mbanq, capped at the loan principal.

[LoanLifecycle.scala:70-115](src/main/scala/lending/service/LoanLifecycle.scala#L70-L115)

### 4. Bad-Deal Management

Two-stage workflow per the brief:

- **Sweeper** (`flagDelinquentLoans`) — loans whose `consecutive_delinquent`
  crosses a threshold get a `bad_deals` row at stage `Eligible`.
- **Auctioneer** (`auctionEligible`) — every `Eligible` row gets listed on the
  collections marketplace, the best bid is accepted, the loan is marked `Sold`,
  and proceeds + discount + buyer ref are recorded.

The marketplace itself is a trait (`CollectionsMarketplace`) so different buyer panels (agency-by-agency, single broker, internal recovery) can plug
in.

## Iron earning its keep

```scala
type Score      = Int :| (GreaterEqual[300] & LessEqual[850])   // FICO domain
type PdBps      = Int :| (GreaterEqual[0]   & LessEqual[10_000]) // 0..100% in bps
type AprBps     = Int :| (GreaterEqual[0]   & LessEqual[100_000]) // anti-usury ceiling
type TermMonths = Int :| (GreaterEqual[1]   & LessEqual[120])    // 1..10y term
type Ein        = String :| (FixedLength[9] & Match["^[0-9]{9}$"])
type Ssn        = String :| (FixedLength[9] & Match["^[0-9]{9}$"]) // crosses the system exactly once
type SsnHash    = String :| (FixedLength[64] & Match["^[0-9a-f]{64}$"]) // HMAC of SSN
type DocuSignEnvelopeId = String :| Match["^[A-Za-z0-9-]{36}$"]
type PlaidItemId = String :| (Not[Blank] & MaxLength[64])
```

`TermMonths` (1..120) eliminates a class of bugs in the amortiser — a 0-month
loan can't be modelled, division-by-zero is structurally impossible. `PdBps`
(0..10_000) is the same idea for probability: every component that consumes
PD trusts the value is in [0%, 100%].

## API

```
POST /auth/signup, /auth/login

POST /kyc                              { fullName, dateOfBirth, ssn, ... }
POST /businesses
GET  /businesses
POST /businesses/{id}/link-bank        { plaidPublicToken }

POST /applications                     { businessId, requestedAmount, ..., purpose }
POST /applications/{id}/underwrite     # runs ScoringEngine + policy
POST /applications/{id}/send-agreement # creates DocuSign envelope
POST /webhooks/docusign                # webhook -> sign -> disburse

GET  /applications
GET  /loans
GET  /loans/{id}/schedule

POST /admin/bad-deals/sweep            # flag delinquent
POST /admin/bad-deals/auction          # list + settle
```

## Running

```bash
createdb lending
psql lending < src/main/resources/db/schema.sql

export DB_USER=postgres
export DB_PASSWORD=postgres
export DB_NAME=lending
export SSN_PEPPER_SEED=replace-me-from-kms
sbt run
```

All upstream integrations (Jumio, Experian, Plaid, DocuSign, Mbanq, PayPal,
collections marketplace, scoring engine) wire to sandbox implementations.
Replace each one when you have credentials; the trait is the seam.

## Intentional seams

- **JWT auth middleware** — routes trust `X-User-Id`. Add a JWT validator
  upstream in production.
- **DocuSign webhook decoder + signature verification** — the route exists,
  the parser is `NotImplementedError` in the sandbox.
- **Delinquency sweeper SQL** — `BadDealService.flagDelinquentLoans` is a
  no-op; the real query is a single `INSERT … FROM loans WHERE consecutive_delinquent >= …`.
- **Repayment scheduler** — `Loans.overdueAsOf` is in place; a daily job
  that calls the rail (Mbanq or PayPal) and updates each schedule row is TODO.
- **Real scoring server** — `ScoringEngine.sandbox` is a linear toy. Plug a
  REST/gRPC client to your model server and keep `model: String` populated.
- **Plaid webhook handlers** — transaction-update webhooks should keep
  Plaid item metadata fresh and re-trigger cashflow snapshots for active
  monitoring (re-score every 30 days, say).
