package lending.service

import lending.domain.*
import lending.db.{Agreements, Applications, Businesses, Cards, Loans, Users}
import lending.external.{DocuSign, Mbanq}

import cats.effect.*
import cats.syntax.all.*

import java.time.{Instant, LocalDate}
import java.util.UUID

/** Once an application is `Approved`, the lifecycle is: sendAgreement -> AgreementSent (DocuSign envelope dispatched)
  * handleSigned -> AgreementSigned (DocuSign webhook fires) disburse -> Disbursed (Mbanq pushes funds, loan + schedule
  * created, card issued)
  */
trait LoanLifecycle[F[_]] {
  def sendAgreement(
      applicationId: ApplicationId
  ): F[Either[LoanLifecycle.Error, LoanAgreement]]
  def handleSigned(
      envelopeId: DocuSignEnvelopeId
  ): F[Either[LoanLifecycle.Error, Loan]]
}

object LoanLifecycle {

  sealed trait Error
  object Error {
    case object ApplicationNotFound extends Error
    case object NotApproved extends Error
    case object AlreadySigned extends Error
    case object NoLinkedAccount extends Error
    case object UnknownEnvelope extends Error
  }

  def make[F[_]: Sync](
      users: Users[F],
      businesses: Businesses[F],
      applications: Applications[F],
      loans: Loans[F],
      agreements: Agreements[F],
      cards: Cards[F],
      docusign: DocuSign[F],
      mbanq: Mbanq[F]
  ): LoanLifecycle[F] = new LoanLifecycle[F] {

    def sendAgreement(
        applicationId: ApplicationId
    ): F[Either[Error, LoanAgreement]] =
      applications.find(applicationId).flatMap {
        case None                                                  => Sync[F].pure(Left(Error.ApplicationNotFound))
        case Some(app) if app.status != ApplicationStatus.Approved =>
          Sync[F].pure(Left(Error.NotApproved))
        case Some(app) =>
          for {
            biz <- businesses.find(app.businessId).map(_.get)
            owner <- users.find(biz.ownerUserId).map(_.get)
            envelope <- docusign.sendForSignature(
              owner.email,
              owner.fullName,
              agreementText(biz, app)
            )
            now <- Sync[F].delay(Instant.now())
            a = LoanAgreement(
              id = AgreementId.assume(UUID.randomUUID()),
              applicationId = applicationId,
              docusignEnvelopeId = envelope.envelopeId,
              documentUrl = envelope.viewUrl,
              sentAt = now,
              signedAt = None,
              declinedAt = None
            )
            saved <- agreements.create(a)
            _ <- applications.updateStatus(
              applicationId,
              ApplicationStatus.AgreementSent
            )
          } yield Right(saved)
      }

    def handleSigned(envelopeId: DocuSignEnvelopeId): F[Either[Error, Loan]] =
      agreements.findByEnvelope(envelopeId).flatMap {
        case None                            => Sync[F].pure(Left(Error.UnknownEnvelope))
        case Some(a) if a.signedAt.isDefined =>
          Sync[F].pure(Left(Error.AlreadySigned))
        case Some(a) =>
          applications.find(a.applicationId).flatMap {
            case None      => Sync[F].pure(Left(Error.ApplicationNotFound))
            case Some(app) =>
              for {
                now <- Sync[F].delay(Instant.now())
                _ <- agreements.markSigned(a.id, now)
                _ <- applications.updateStatus(
                  a.applicationId,
                  ApplicationStatus.AgreementSigned
                )
                out <- disburse(app, now)
              } yield out
          }
      }

    /** Money-out step: create the loan + schedule, push funds via Mbanq, issue the virtual card.
      */
    private def disburse(
        app: LoanApplication,
        now: Instant
    ): F[Either[Error, Loan]] =
      businesses.linkedAccountsFor(app.businessId).flatMap {
        case Nil         => Sync[F].pure(Left(Error.NoLinkedAccount))
        case linked :: _ =>
          (app.pricedAmount, app.pricedTermMonths, app.pricedAprBps) match {
            case (Some(amount), Some(term), Some(apr)) =>
              for {
                loanRow <- Sync[F].pure(
                  Loan(
                    id = LoanId.assume(UUID.randomUUID()),
                    applicationId = app.id,
                    businessId = app.businessId,
                    principalMinor = amount,
                    currency = app.currency,
                    termMonths = term,
                    aprBps = apr,
                    status = LoanStatus.Active,
                    outstandingMinor = amount.value,
                    disbursedAt = now,
                    consecutiveDelinquent = 0
                  )
                )
                schedule = amortise(loanRow, today = now)
                saved <- loans.insert(loanRow, schedule)
                _ <- mbanq.disburse(linked, amount, s"loan:${loanRow.id.value}")
                issued <- mbanq.issueVirtualCard(loanRow.id, amount)
                _ <- cards.insert(
                  VirtualCard(
                    id = CardId.assume(UUID.randomUUID()),
                    loanId = loanRow.id,
                    token = issued.token,
                    last4 = issued.last4,
                    expiry = issued.expiry,
                    status = CardStatus.Issued,
                    spendLimitMinor = amount,
                    issuedAt = now
                  )
                )
                _ <- applications.updateStatus(
                  app.id,
                  ApplicationStatus.Disbursed
                )
              } yield Right(saved)
            case _ => Sync[F].pure(Left(Error.NotApproved))
          }
      }

    private def agreementText(b: Business, app: LoanApplication): String =
      s"""Loan agreement between ${b.name.value} and the Lender.
         |Amount: ${app.pricedAmount
          .map(_.value)
          .getOrElse(0L)} ${app.currency.value} (minor units).
         |Term: ${app.pricedTermMonths
          .map(_.value)
          .getOrElse(0)} months at ${app.pricedAprBps
          .map(_.value)
          .getOrElse(0)} bps APR.
         |""".stripMargin
  }

  /** Equal-payment amortisation. Returns one [[RepaymentSchedule]] per month.
    */
  def amortise(loan: Loan, today: Instant): List[RepaymentSchedule] = {
    val term = loan.termMonths.value
    val principal = BigDecimal(loan.principalMinor.value)
    val monthlyR =
      BigDecimal(loan.aprBps.value) / BigDecimal(10_000) / BigDecimal(12)
    val payment =
      if (monthlyR == 0)
        (principal / term).setScale(0, BigDecimal.RoundingMode.HALF_UP).toLong
      else {
        val factor = (BigDecimal(1) + monthlyR).pow(term)
        ((principal * monthlyR * factor) / (factor - 1))
          .setScale(0, BigDecimal.RoundingMode.HALF_UP)
          .toLong
      }
    var remaining = principal
    (1 to term).toList.map { seq =>
      val interest = (remaining * monthlyR)
        .setScale(0, BigDecimal.RoundingMode.HALF_UP)
        .toLong
        .max(1L)
      val principalPart = (payment - interest).max(1L)
      remaining = remaining - BigDecimal(principalPart)
      val dueOn = LocalDate
        .ofInstant(today, java.time.ZoneOffset.UTC)
        .plusMonths(seq.toLong)
      RepaymentSchedule(
        id = RepaymentId.assume(UUID.randomUUID()),
        loanId = loan.id,
        sequence = seq,
        dueOn = dueOn,
        principalMinor = PositiveAmount.assume(principalPart),
        interestMinor = PositiveAmount.assume(interest),
        totalMinor = PositiveAmount.assume(principalPart + interest),
        status = RepaymentStatus.Scheduled,
        capturedAt = None,
        railRef = None
      )
    }
  }
}
