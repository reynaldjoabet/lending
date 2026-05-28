package lending.db

import lending.domain.*

import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.*
import io.circe.parser.parse as parseJson
import io.circe.syntax.*
import skunk.*
import skunk.codec.all.*
import org.typelevel.twiddles.syntax.*
import io.github.iltotore.iron.constraint.all.*
import io.github.iltotore.iron.skunk.*
import io.github.iltotore.iron.circe.given
import java.time.{Instant, LocalDate, ZoneOffset}

object Codecs {

  // ---- ids ----
  val userId: Codec[UserId] = uuid.imap(UserId.assume)(_.value)
  val businessId: Codec[BusinessId] = uuid.imap(BusinessId.assume)(_.value)
  val applicationId: Codec[ApplicationId] =
    uuid.imap(ApplicationId.assume)(_.value)
  val scoreId: Codec[ScoreId] = uuid.imap(ScoreId.assume)(_.value)
  val loanId: Codec[LoanId] = uuid.imap(LoanId.assume)(_.value)
  val agreementId: Codec[AgreementId] = uuid.imap(AgreementId.assume)(_.value)
  val repaymentId: Codec[RepaymentId] = uuid.imap(RepaymentId.assume)(_.value)
  val linkedAccountId: Codec[LinkedAccountId] =
    uuid.imap(LinkedAccountId.assume)(_.value)
  val cardId: Codec[CardId] = uuid.imap(CardId.assume)(_.value)
  val badDealId: Codec[BadDealId] = uuid.imap(BadDealId.assume)(_.value)

  // ---- scalars ----
  val email: Codec[Email] =
    varchar(254).refined[EmailConstraint].imap(Email.assume)(_.value)
  val phone: Codec[PhoneE164] = varchar(16)
    .refined[Match["""^\+[1-9][0-9]{7,14}$"""]]
    .imap(PhoneE164.assume)(_.value)
  val passwordHash: Codec[PasswordHash] =
    varchar(120).imap(PasswordHash.assume)(_.value)
  val fullName: Codec[FullName] = varchar(200)
    .refined[Not[Blank] & MaxLength[200]]
    .imap(FullName.assume)(_.value)
  val businessName: Codec[BusinessName] = varchar(200)
    .refined[Not[Blank] & MaxLength[200]]
    .imap(BusinessName.assume)(_.value)
  val ein: Codec[Ein] = bpchar(9)
    .refined[FixedLength[9] & Match["^[0-9]{9}$"]]
    .imap(Ein.assume)(_.value)

  val ssnHash: Codec[SsnHash] = bpchar(64)
    .refined[FixedLength[64] & Match["^[0-9a-f]{64}$"]]
    .imap(SsnHash.assume)(_.value)
  val ssnLast4: Codec[SsnLast4] = bpchar(4)
    .refined[FixedLength[4] & Match["^[0-9]{4}$"]]
    .imap(SsnLast4.assume)(_.value)

  val amountMinor: Codec[AmountMinor] = int8.imap(AmountMinor.assume)(_.value)
  val positiveAmount: Codec[PositiveAmount] =
    int8.refined[Positive].imap(PositiveAmount.assume)(_.value)
  val currency: Codec[CurrencyCode] =
    bpchar(3).refined[Match["^[A-Z]{3}$"]].imap(CurrencyCode.assume)(_.value)
  val score: Codec[Score] =
    int4.refined[GreaterEqual[300] & LessEqual[850]].imap(Score.assume)(_.value)
  val aprBps: Codec[AprBps] = int4
    .refined[GreaterEqual[0] & LessEqual[100_000]]
    .imap(AprBps.assume)(_.value)
  val pdBps: Codec[PdBps] = int4
    .refined[GreaterEqual[0] & LessEqual[10_000]]
    .imap(PdBps.assume)(_.value)
  val termMonths: Codec[TermMonths] = int4
    .refined[GreaterEqual[1] & LessEqual[120]]
    .imap(TermMonths.assume)(_.value)

  val cardToken: Codec[CardToken] = varchar(128)
    .refined[Not[Blank] & MaxLength[128]]
    .imap(CardToken.assume)(_.value)
  val last4: Codec[Last4] = bpchar(4)
    .refined[FixedLength[4] & Match["^[0-9]{4}$"]]
    .imap(Last4.assume)(_.value)
  val cardExpiry: Codec[CardExpiry] = varchar(5)
    .refined[Match["""^(0[1-9]|1[0-2])/[0-9]{2}$"""]]
    .imap(CardExpiry.assume)(_.value)
  val routingNumber: Codec[RoutingNumber] = bpchar(9)
    .refined[FixedLength[9] & Match["^[0-9]{9}$"]]
    .imap(RoutingNumber.assume)(_.value)
  val plaidItemId: Codec[PlaidItemId] = varchar(64)
    .refined[Not[Blank] & MaxLength[64]]
    .imap(PlaidItemId.assume)(_.value)
  val docusignEnvId: Codec[DocuSignEnvelopeId] = bpchar(36)
    .refined[Match["^[A-Za-z0-9-]{36}$"]]
    .imap(DocuSignEnvelopeId.assume)(_.value)

  val title: Codec[Title] = varchar(200)
    .refined[Not[Blank] & MaxLength[200]]
    .imap(Title.assume)(_.value)
  val body: Codec[Body] =
    text.refined[Not[Blank] & MaxLength[8000]].imap(Body.assume)(_.value)

  // ---- enums ----

  val role: Codec[Role] = varchar(16).eimap(Role.parse)(Role.render)

  val kycStatus: Codec[KycStatus] = varchar(16).eimap[KycStatus] {
    case "not_started" => Right(KycStatus.NotStarted)
    case "pending"     => Right(KycStatus.Pending)
    case "approved"    => Right(KycStatus.Approved)
    case "rejected"    => Right(KycStatus.Rejected)
    case o             => Left(s"unknown kyc status: $o")
  } {
    case KycStatus.NotStarted => "not_started"
    case KycStatus.Pending    => "pending"
    case KycStatus.Approved   => "approved"
    case KycStatus.Rejected   => "rejected"
  }

  val applicationStatus: Codec[ApplicationStatus] =
    varchar(24).eimap[ApplicationStatus] {
      case "submitted"        => Right(ApplicationStatus.Submitted)
      case "underwriting"     => Right(ApplicationStatus.Underwriting)
      case "approved"         => Right(ApplicationStatus.Approved)
      case "declined"         => Right(ApplicationStatus.Declined)
      case "withdrawn"        => Right(ApplicationStatus.Withdrawn)
      case "agreement_sent"   => Right(ApplicationStatus.AgreementSent)
      case "agreement_signed" => Right(ApplicationStatus.AgreementSigned)
      case "disbursed"        => Right(ApplicationStatus.Disbursed)
      case o                  => Left(s"unknown application status: $o")
    } {
      case ApplicationStatus.Submitted       => "submitted"
      case ApplicationStatus.Underwriting    => "underwriting"
      case ApplicationStatus.Approved        => "approved"
      case ApplicationStatus.Declined        => "declined"
      case ApplicationStatus.Withdrawn       => "withdrawn"
      case ApplicationStatus.AgreementSent   => "agreement_sent"
      case ApplicationStatus.AgreementSigned => "agreement_signed"
      case ApplicationStatus.Disbursed       => "disbursed"
    }

  val loanStatus: Codec[LoanStatus] = varchar(16).eimap[LoanStatus] {
    case "active"      => Right(LoanStatus.Active)
    case "delinquent"  => Right(LoanStatus.Delinquent)
    case "paid_off"    => Right(LoanStatus.PaidOff)
    case "charged_off" => Right(LoanStatus.ChargedOff)
    case "sold"        => Right(LoanStatus.Sold)
    case o             => Left(s"unknown loan status: $o")
  } {
    case LoanStatus.Active     => "active"
    case LoanStatus.Delinquent => "delinquent"
    case LoanStatus.PaidOff    => "paid_off"
    case LoanStatus.ChargedOff => "charged_off"
    case LoanStatus.Sold       => "sold"
  }

  val repaymentStatus: Codec[RepaymentStatus] =
    varchar(12).eimap[RepaymentStatus] {
      case "scheduled" => Right(RepaymentStatus.Scheduled)
      case "paid"      => Right(RepaymentStatus.Paid)
      case "failed"    => Right(RepaymentStatus.Failed)
      case "skipped"   => Right(RepaymentStatus.Skipped)
      case o           => Left(s"unknown repayment status: $o")
    } { _.toString.toLowerCase }

  val cardStatus: Codec[CardStatus] = varchar(12).eimap[CardStatus] {
    case "issued"    => Right(CardStatus.Issued)
    case "active"    => Right(CardStatus.Active)
    case "frozen"    => Right(CardStatus.Frozen)
    case "cancelled" => Right(CardStatus.Cancelled)
    case o           => Left(s"unknown card status: $o")
  } { _.toString.toLowerCase }

  val badDealStage: Codec[BadDealStage] = varchar(16).eimap[BadDealStage] {
    case "eligible"  => Right(BadDealStage.Eligible)
    case "listed"    => Right(BadDealStage.Listed)
    case "sold"      => Right(BadDealStage.Sold)
    case "recovered" => Right(BadDealStage.Recovered)
    case o           => Left(s"unknown bad deal stage: $o")
  } { _.toString.toLowerCase }

  private val instant: Codec[java.time.Instant] =
    timestamptz.imap(_.toInstant)(_.atOffset(ZoneOffset.UTC))

  /** ScoringInputs persists as JSONB. */
  given Encoder[ScoringInputs] = deriveEncoder
  given Decoder[ScoringInputs] = deriveDecoder

  val scoringInputs: Codec[ScoringInputs] =
    skunk.codec.all.text.eimap[ScoringInputs] { s =>
      parseJson(s).flatMap(_.as[ScoringInputs]).left.map(_.getMessage)
    } { in => in.asJson.noSpaces }

  // ---- aggregates ----
  // skunk 1.x's `*:` chain produces right-nested Tuple2 pairs `(A, (B, (C, ...)))`.
  // Case class Mirrors are flat tuples `(A, B, C, ...)`, so `.to[T]` cannot derive
  // an Iso for >2 fields here. We bridge with explicit `imap` and nested destructuring.

  val user: Codec[User] =
    (userId *: email *: phone *: passwordHash *: fullName *: role *: ssnHash.opt *: ssnLast4.opt *:
      date.opt *: kycStatus *: instant).imap { case (id, (em, (ph, (pw, (fn, (rl, (sh, (sl, (dob, (kyc, ca)))))))))) =>
      User(id, em, ph, pw, fn, rl, sh, sl, dob, kyc, ca)
    }(u =>
      (
        u.id,
        (
          u.email,
          (
            u.phone,
            (
              u.passwordHash,
              (
                u.fullName,
                (
                  u.role,
                  (
                    u.ssnHash,
                    (u.ssnLast4, (u.dateOfBirth, (u.kycStatus, u.createdAt)))
                  )
                )
              )
            )
          )
        )
      )
    )

  val business: Codec[Business] =
    (businessId *: userId *: businessName *: ein *: varchar(64) *: date *:
      positiveAmount *: positiveAmount *: instant).imap { case (id, (own, (nm, (en, (ind, (fd, (ar, (mr, ca)))))))) =>
      Business(id, own, nm, en, ind, fd, ar, mr, ca)
    }(b =>
      (
        b.id,
        (
          b.ownerUserId,
          (
            b.name,
            (
              b.ein,
              (
                b.industry,
                (
                  b.foundedOn,
                  (b.annualRevenueMinor, (b.monthlyRevenueMinor, b.createdAt))
                )
              )
            )
          )
        )
      )
    )

  val linkedAccount: Codec[LinkedAccount] =
    (linkedAccountId *: businessId *: plaidItemId *: routingNumber *: last4 *: fullName *: instant)
      .imap { case (id, (bid, (pid, (rn, (l4, (hn, at)))))) =>
        LinkedAccount(id, bid, pid, rn, l4, hn, at)
      }(a =>
        (
          a.id,
          (
            a.businessId,
            (
              a.plaidItemId,
              (a.routingNumber, (a.last4, (a.holderName, a.addedAt)))
            )
          )
        )
      )

  val loanApplication: Codec[LoanApplication] =
    (applicationId *: businessId *: positiveAmount *: currency *: termMonths *: body *:
      applicationStatus *: scoreId.opt *: positiveAmount.opt *: termMonths.opt *: aprBps.opt *:
      body.opt *: instant *: instant.opt).imap {
      case (
            id,
            (
              bid,
              (
                ra,
                (
                  cur,
                  (rtm, (pur, (st, (sid, (pa, (ptm, (pab, (dr, (sa, da)))))))))
                )
              )
            )
          ) =>
        LoanApplication(
          id,
          bid,
          ra,
          cur,
          rtm,
          pur,
          st,
          sid,
          pa,
          ptm,
          pab,
          dr,
          sa,
          da
        )
    }(a =>
      (
        a.id,
        (
          a.businessId,
          (
            a.requestedAmount,
            (
              a.currency,
              (
                a.requestedTermMonths,
                (
                  a.purpose,
                  (
                    a.status,
                    (
                      a.scoreId,
                      (
                        a.pricedAmount,
                        (
                          a.pricedTermMonths,
                          (
                            a.pricedAprBps,
                            (a.declinedReason, (a.submittedAt, a.decidedAt))
                          )
                        )
                      )
                    )
                  )
                )
              )
            )
          )
        )
      )
    )

  val scoringResult: Codec[ScoringResult] =
    (scoreId *: applicationId *: varchar(64) *: score *: pdBps *: bool *:
      positiveAmount *: termMonths *: aprBps *: scoringInputs *: instant).imap {
      case (
            id,
            (aid, (mdl, (sc, (pd, (apr, (ra, (rtm, (rab, (inp, sa)))))))))
          ) =>
        ScoringResult(id, aid, mdl, sc, pd, apr, ra, rtm, rab, inp, sa)
    }(r =>
      (
        r.id,
        (
          r.applicationId,
          (
            r.model,
            (
              r.score,
              (
                r.pdBps,
                (
                  r.approve,
                  (
                    r.recommendedAmount,
                    (
                      r.recommendedTermMonths,
                      (r.recommendedAprBps, (r.inputs, r.scoredAt))
                    )
                  )
                )
              )
            )
          )
        )
      )
    )

  val loanAgreement: Codec[LoanAgreement] =
    (agreementId *: applicationId *: docusignEnvId *: varchar(512) *:
      instant *: instant.opt *: instant.opt).imap { case (id, (aid, (de, (du, (st, (si, dec)))))) =>
      LoanAgreement(id, aid, de, du, st, si, dec)
    }(a =>
      (
        a.id,
        (
          a.applicationId,
          (
            a.docusignEnvelopeId,
            (a.documentUrl, (a.sentAt, (a.signedAt, a.declinedAt)))
          )
        )
      )
    )

  val loan: Codec[Loan] =
    (loanId *: applicationId *: businessId *: positiveAmount *: currency *: termMonths *: aprBps *:
      loanStatus *: int8 *: instant *: int4).imap {
      case (
            id,
            (aid, (bid, (pr, (cur, (tm, (apr, (st, (out, (da, cd)))))))))
          ) =>
        Loan(id, aid, bid, pr, cur, tm, apr, st, out, da, cd)
    }(l =>
      (
        l.id,
        (
          l.applicationId,
          (
            l.businessId,
            (
              l.principalMinor,
              (
                l.currency,
                (
                  l.termMonths,
                  (
                    l.aprBps,
                    (
                      l.status,
                      (
                        l.outstandingMinor,
                        (l.disbursedAt, l.consecutiveDelinquent)
                      )
                    )
                  )
                )
              )
            )
          )
        )
      )
    )

  val repayment: Codec[RepaymentSchedule] =
    (repaymentId *: loanId *: int4 *: date *: positiveAmount *: positiveAmount *: positiveAmount *:
      repaymentStatus *: instant.opt *: varchar(128).opt).imap {
      case (id, (lid, (sq, (dt, (pr, (in_, (tt, (st, (ca, rr))))))))) =>
        RepaymentSchedule(id, lid, sq, dt, pr, in_, tt, st, ca, rr)
    }(r =>
      (
        r.id,
        (
          r.loanId,
          (
            r.sequence,
            (
              r.dueOn,
              (
                r.principalMinor,
                (
                  r.interestMinor,
                  (r.totalMinor, (r.status, (r.capturedAt, r.railRef)))
                )
              )
            )
          )
        )
      )
    )

  val virtualCard: Codec[VirtualCard] =
    (cardId *: loanId *: cardToken *: last4 *: cardExpiry *: cardStatus *: positiveAmount *: instant)
      .imap { case (id, (lid, (tk, (l4, (ex, (st, (sl, ia))))))) =>
        VirtualCard(id, lid, tk, l4, ex, st, sl, ia)
      }(c =>
        (
          c.id,
          (
            c.loanId,
            (
              c.token,
              (c.last4, (c.expiry, (c.status, (c.spendLimitMinor, c.issuedAt))))
            )
          )
        )
      )

  val badDeal: Codec[BadDeal] =
    (badDealId *: loanId *: badDealStage *: int4.opt *: positiveAmount.opt *: varchar(
      128
    ).opt *:
      instant *: instant.opt *: instant.opt).imap { case (id, (lid, (st, (db, (sp, (br, (fa, (la, sa)))))))) =>
      BadDeal(id, lid, st, db, sp, br, fa, la, sa)
    }(d =>
      (
        d.id,
        (
          d.loanId,
          (
            d.stage,
            (
              d.discountBps,
              (
                d.saleProceedsMinor,
                (d.buyerRef, (d.flaggedAt, (d.listedAt, d.soldAt)))
              )
            )
          )
        )
      )
    )
}
