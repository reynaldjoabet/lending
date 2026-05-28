package lending.domain

import java.time.{Instant, LocalDate}

// ---------- Users & businesses ----------

final case class User(
    id: UserId,
    email: Email,
    phone: PhoneE164,
    passwordHash: PasswordHash,
    fullName: FullName,
    role: Role,
    ssnHash: Option[SsnHash],
    ssnLast4: Option[SsnLast4],
    dateOfBirth: Option[LocalDate],
    kycStatus: KycStatus,
    createdAt: Instant
)

final case class Business(
    id: BusinessId,
    ownerUserId: UserId,
    name: BusinessName,
    ein: Ein,
    industry: String,
    foundedOn: LocalDate,
    annualRevenueMinor: PositiveAmount,
    monthlyRevenueMinor: PositiveAmount,
    createdAt: Instant
)

// ---------- Linked accounts (Plaid) ----------

final case class LinkedAccount(
    id: LinkedAccountId,
    businessId: BusinessId,
    plaidItemId: PlaidItemId,
    routingNumber: RoutingNumber,
    last4: Last4,
    holderName: FullName,
    addedAt: Instant
)

// ---------- Applications ----------

final case class LoanApplication(
    id: ApplicationId,
    businessId: BusinessId,
    requestedAmount: PositiveAmount,
    currency: CurrencyCode,
    requestedTermMonths: TermMonths,
    purpose: Body,
    status: ApplicationStatus,
    /** Score id linking to the underwriting decision, populated post-underwriting.
      */
    scoreId: Option[ScoreId],
    /** Set when the underwriter approves; the priced terms the applicant must accept.
      */
    pricedAmount: Option[PositiveAmount],
    pricedTermMonths: Option[TermMonths],
    pricedAprBps: Option[AprBps],
    declinedReason: Option[Body],
    submittedAt: Instant,
    decidedAt: Option[Instant]
)

// ---------- Scoring ----------

/** Inputs the scoring engine consumed. We persist a snapshot so admins can reproduce the decision later.
  */
final case class ScoringInputs(
    bureauScore: Option[Score],
    monthsInBusiness: Int,
    monthlyRevenueMinor: Long,
    avgBalanceMinor: Long,
    nsfCount12m: Int,
    industry: String
)

/** Outputs the scoring engine produced. The `model` string identifies which version of the AI ensemble made the call —
  * critical for audit.
  */
final case class ScoringResult(
    id: ScoreId,
    applicationId: ApplicationId,
    model: String, // e.g. "ensemble_v3.1"
    score: Score,
    pdBps: PdBps,
    approve: Boolean,
    /** Recommended priced terms — may be tightened by policy before display. */
    recommendedAmount: PositiveAmount,
    recommendedTermMonths: TermMonths,
    recommendedAprBps: AprBps,
    inputs: ScoringInputs,
    scoredAt: Instant
)

// ---------- Agreements ----------

final case class LoanAgreement(
    id: AgreementId,
    applicationId: ApplicationId,
    docusignEnvelopeId: DocuSignEnvelopeId,
    /** PDF stored externally; we keep just the URL. */
    documentUrl: String,
    sentAt: Instant,
    signedAt: Option[Instant],
    declinedAt: Option[Instant]
)

// ---------- Loans & repayments ----------

final case class Loan(
    id: LoanId,
    applicationId: ApplicationId,
    businessId: BusinessId,
    principalMinor: PositiveAmount,
    currency: CurrencyCode,
    termMonths: TermMonths,
    aprBps: AprBps,
    status: LoanStatus,
    /** Outstanding principal — decrements as repayments capture. */
    outstandingMinor: Long,
    disbursedAt: Instant,
    /** Number of consecutive failed/late repayments. Drives delinquency tagging.
      */
    consecutiveDelinquent: Int
)

final case class RepaymentSchedule(
    id: RepaymentId,
    loanId: LoanId,
    sequence: Int, // 1..termMonths
    dueOn: LocalDate,
    principalMinor: PositiveAmount,
    interestMinor: PositiveAmount,
    totalMinor: PositiveAmount,
    status: RepaymentStatus,
    capturedAt: Option[Instant],
    /** Upstream payment-rail reference once captured (PayPal / Mbanq). */
    railRef: Option[String]
)

// ---------- Virtual credit card (post-disbursement) ----------

final case class VirtualCard(
    id: CardId,
    loanId: LoanId,
    /** Mbanq's card token. */
    token: CardToken,
    last4: Last4,
    expiry: CardExpiry,
    status: CardStatus,
    spendLimitMinor: PositiveAmount,
    issuedAt: Instant
)

// ---------- Bad-deal management ----------

/** One row per nonperforming loan. The workflow: Eligible - flagged by the delinquency sweeper Listed - posted to the
  * collection-agency marketplace Sold - collection agency accepted, funds received Recovered - sold debt has been
  * recovered by the agency
  */
final case class BadDeal(
    id: BadDealId,
    loanId: LoanId,
    stage: BadDealStage,
    /** Discount applied when sold (basis points off principal). */
    discountBps: Option[Int],
    /** Net proceeds from the sale. */
    saleProceedsMinor: Option[PositiveAmount],
    /** Collection agency identifier. */
    buyerRef: Option[String],
    flaggedAt: Instant,
    listedAt: Option[Instant],
    soldAt: Option[Instant]
)
