package lending.external

import lending.domain.*

import cats.effect.*

import java.time.LocalDate

// ---------- KYC: Jumio + Acuant ----------

trait Kyc[F[_]] {
  def submit(
      userId: UserId,
      fullName: FullName,
      dob: LocalDate,
      ssn: Ssn,
      documentImageUrl: String,
      selfieUrl: String
  ): F[Kyc.Decision]
  def refresh(ref: String): F[Kyc.Decision]
}

object Kyc {
  enum Decision {
    case Pending(ref: String); case Approved; case Rejected(reason: String)
  }

  def sandbox[F[_]: Sync]: F[Kyc[F]] = Sync[F].pure(new Kyc[F] {
    def submit(
        userId: UserId,
        fullName: FullName,
        dob: LocalDate,
        ssn: Ssn,
        documentImageUrl: String,
        selfieUrl: String
    ): F[Decision] = Sync[F].pure(Decision.Approved)
    def refresh(ref: String): F[Decision] = Sync[F].pure(Decision.Approved)
  })
}

// ---------- Credit bureau (Experian) ----------

trait CreditBureau[F[_]] {
  def report(ssnHash: SsnHash): F[CreditBureau.Report]
}

object CreditBureau {
  final case class Report(
      score: Score,
      openTrades: Int,
      delinquencies12m: Int,
      utilisationBps: Int
  )

  def sandbox[F[_]: Sync]: F[CreditBureau[F]] =
    Sync[F].pure(new CreditBureau[F] {
      def report(ssnHash: SsnHash): F[Report] =
        Sync[F].delay(
          Report(Score.assume(640 + scala.util.Random.nextInt(120)), 3, 0, 3500)
        )
    })
}

// ---------- Plaid (bank linking + transaction history) ----------

trait Plaid[F[_]] {

  /** Exchange a Link public token for a permanent item id. */
  def exchangePublicToken(publicToken: String): F[Plaid.LinkResult]

  /** Sum of inflows / outflows / nsf events for the last `windowDays`, used by underwriting.
    */
  def cashflowSnapshot(itemId: PlaidItemId, windowDays: Int): F[Plaid.Cashflow]
}

object Plaid {
  final case class LinkResult(
      itemId: PlaidItemId,
      routing: RoutingNumber,
      last4: Last4,
      holderName: FullName
  )
  final case class Cashflow(
      inflowMinor: Long,
      outflowMinor: Long,
      avgBalanceMinor: Long,
      nsfCount: Int
  )

  def sandbox[F[_]: Sync]: F[Plaid[F]] = Sync[F].pure(new Plaid[F] {
    def exchangePublicToken(publicToken: String): F[LinkResult] =
      Sync[F].pure(
        LinkResult(
          PlaidItemId.assume(
            "plaid_" + java.util.UUID.randomUUID().toString.take(20)
          ),
          RoutingNumber.assume("121000358"),
          Last4.assume("0001"),
          FullName.assume("Sandbox Account Holder")
        )
      )
    def cashflowSnapshot(itemId: PlaidItemId, windowDays: Int): F[Cashflow] =
      Sync[F].pure(Cashflow(1_200_000L, 950_000L, 480_000L, 0))
  })
}

// ---------- AI scoring engine ----------

/** The brief specifies an *ensemble of statistical + ML algorithms*. Behind this trait sits whatever model is current
  * (gradient-boosted trees, neural net, rule-based fallback). The `model` field on [[ScoringResult]] identifies which
  * version made each call, so the audit story is intact across model upgrades.
  */
trait ScoringEngine[F[_]] {
  def score(
      applicationId: ApplicationId,
      inputs: ScoringInputs
  ): F[ScoringResult]
}

object ScoringEngine {

  /** Sandbox: deterministic linear policy. Replace with a real model server (REST / gRPC).
    */
  def sandbox[F[_]: Sync]: F[ScoringEngine[F]] =
    Sync[F].pure(new ScoringEngine[F] {
      def score(
          applicationId: ApplicationId,
          inputs: ScoringInputs
      ): F[ScoringResult] = Sync[F].delay {
        val base = inputs.bureauScore.fold(620)(_.value)
        val months = inputs.monthsInBusiness.min(60)
        val nsfPen = (inputs.nsfCount12m * 15).min(150)
        val rawScore = (base + months / 2 - nsfPen).max(300).min(850)
        val s = Score.applyUnsafe(rawScore)
        // PD heuristic: 1000 - (score-300)*1.5, clipped to 0..10_000 bps.
        val pdBps = PdBps.applyUnsafe(
          ((1000.0 - (rawScore - 300) * 1.5).max(0.0).min(10_000.0)).toInt
        )
        val approve = rawScore >= 600 && inputs.nsfCount12m < 3
        val (amount, term, apr) = (rawScore, inputs.monthlyRevenueMinor) match {
          case (s, _) if s >= 740 =>
            (
              PositiveAmount.applyUnsafe(5_000_000L),
              TermMonths.applyUnsafe(24),
              AprBps.applyUnsafe(999)
            ) // $50k, 24m, 9.99%
          case (s, _) if s >= 680 =>
            (
              PositiveAmount.applyUnsafe(2_500_000L),
              TermMonths.applyUnsafe(18),
              AprBps.applyUnsafe(1499)
            ) // $25k, 18m, 14.99%
          case _ =>
            (
              PositiveAmount.applyUnsafe(1_000_000L),
              TermMonths.applyUnsafe(12),
              AprBps.applyUnsafe(2299)
            ) // $10k, 12m, 22.99%
        }
        ScoringResult(
          id = ScoreId.assume(java.util.UUID.randomUUID()),
          applicationId = applicationId,
          model = "ensemble_v0.1_sandbox",
          score = s,
          pdBps = pdBps,
          approve = approve,
          recommendedAmount = amount,
          recommendedTermMonths = term,
          recommendedAprBps = apr,
          inputs = inputs,
          scoredAt = java.time.Instant.now()
        )
      }
    })
}

// ---------- DocuSign ----------

trait DocuSign[F[_]] {

  /** Create + dispatch an envelope. Returns the envelope id + a viewer URL the app can deep-link to.
    */
  def sendForSignature(
      applicantEmail: Email,
      applicantName: FullName,
      agreementBody: String
  ): F[DocuSign.Sent]

  /** Webhook handler — convert an inbound payload into a decision. */
  def decodeWebhook(payload: String): F[DocuSign.Event]
}

object DocuSign {
  final case class Sent(envelopeId: DocuSignEnvelopeId, viewUrl: String)
  enum Event {
    case Signed(envelopeId: DocuSignEnvelopeId);
    case Declined(envelopeId: DocuSignEnvelopeId, reason: String)
  }

  def sandbox[F[_]: Sync]: F[DocuSign[F]] = Sync[F].pure(new DocuSign[F] {
    def sendForSignature(
        applicantEmail: Email,
        name: FullName,
        body: String
    ): F[Sent] = Sync[F].delay {
      val env = DocuSignEnvelopeId.assume(java.util.UUID.randomUUID().toString)
      Sent(env, s"https://docusign.sandbox.invalid/envelope/${env.value}")
    }
    def decodeWebhook(payload: String): F[Event] =
      Sync[F].raiseError(
        new NotImplementedError("provide a real DocuSign webhook decoder")
      )
  })
}

// ---------- Mbanq (BaaS — accounts, cards, payments) ----------

trait Mbanq[F[_]] {
  def issueVirtualCard(
      loanId: LoanId,
      spendLimit: PositiveAmount
  ): F[Mbanq.Issued]
  def disburse(
      linkedAccount: LinkedAccount,
      amount: PositiveAmount,
      reference: String
  ): F[String]
  def collect(
      linkedAccount: LinkedAccount,
      amount: PositiveAmount,
      reference: String
  ): F[String]
}

object Mbanq {
  final case class Issued(token: CardToken, last4: Last4, expiry: CardExpiry)

  def sandbox[F[_]: Sync]: F[Mbanq[F]] = Sync[F].pure(new Mbanq[F] {
    def issueVirtualCard(
        loanId: LoanId,
        spendLimit: PositiveAmount
    ): F[Issued] =
      Sync[F].delay(
        Issued(
          CardToken.assume(
            "mbq_" + java.util.UUID.randomUUID().toString.take(20)
          ),
          Last4.assume(f"${scala.util.Random.nextInt(10_000)}%04d"),
          CardExpiry.assume("12/30")
        )
      )
    def disburse(
        linkedAccount: LinkedAccount,
        amount: PositiveAmount,
        reference: String
    ): F[String] =
      Sync[F].delay(
        "mbq_disburse_" + java.util.UUID.randomUUID().toString.take(16)
      )
    def collect(
        linkedAccount: LinkedAccount,
        amount: PositiveAmount,
        reference: String
    ): F[String] =
      Sync[F].delay(
        "mbq_collect_" + java.util.UUID.randomUUID().toString.take(16)
      )
  })
}

// ---------- PayPal (alternative payment rail) ----------

trait PayPalRail[F[_]] {
  def collect(
      payerEmail: Email,
      amount: PositiveAmount,
      reference: String
  ): F[String]
}

object PayPalRail {
  def sandbox[F[_]: Sync]: F[PayPalRail[F]] = Sync[F].pure(new PayPalRail[F] {
    def collect(
        payerEmail: Email,
        amount: PositiveAmount,
        reference: String
    ): F[String] =
      Sync[F].delay("pp_" + java.util.UUID.randomUUID().toString.take(16))
  })
}

// ---------- Collection-agency marketplace (bad-deal disposal) ----------

trait CollectionsMarketplace[F[_]] {

  /** Post a nonperforming loan to one or more buyers. Returns the listing id.
    */
  def list(loan: Loan): F[String]

  /** Accept a bid and settle. Returns proceeds + buyer ref. */
  def settleBest(listingRef: String): F[CollectionsMarketplace.Settlement]
}

object CollectionsMarketplace {
  final case class Settlement(
      buyerRef: String,
      proceeds: PositiveAmount,
      discountBps: Int
  )

  def sandbox[F[_]: Sync]: F[CollectionsMarketplace[F]] =
    Sync[F].pure(new CollectionsMarketplace[F] {
      def list(loan: Loan): F[String] =
        Sync[F].delay(
          "listing_" + java.util.UUID.randomUUID().toString.take(12)
        )
      def settleBest(listingRef: String): F[Settlement] = Sync[F].delay {
        // 30 cents on the dollar; a real auction would pick highest bid.
        val discount = 7_000 // 70% off principal in bps
        Settlement(
          buyerRef = "agency_" + java.util.UUID.randomUUID().toString.take(8),
          proceeds = PositiveAmount.assume(300_000L),
          discountBps = discount
        )
      }
    })
}
