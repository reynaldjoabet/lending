package lending.service

import lending.domain.*
import lending.db.{Applications, Businesses, Users}
import lending.external.{CreditBureau, Plaid, ScoringEngine}

import cats.effect.*
import cats.syntax.all.*

/** The end-to-end underwriting flow.
  *
  *   1. Pull the bureau report (Experian) — requires KYC-validated SSN hash.
  *   2. Pull Plaid cashflow over the linked accounts.
  *   3. Stamp inputs + feed them to the scoring engine.
  *   4. Apply policy guardrails on top of the model's recommendation.
  *   5. Persist the decision and flip the application status.
  *
  * The engine output is the *recommendation*. Policy lives here, separate from the model, so risk officers can tighten
  * without retraining.
  */
trait UnderwritingService[F[_]] {
  def underwrite(
      applicationId: ApplicationId
  ): F[Either[UnderwritingService.Error, ScoringResult]]
}

object UnderwritingService {

  sealed trait Error
  object Error {
    case object ApplicationNotFound extends Error
    case object BusinessNotFound extends Error
    case object KycMissing extends Error
    case object NoLinkedAccount extends Error
  }

  def make[F[_]: Concurrent](
      users: Users[F],
      businesses: Businesses[F],
      applications: Applications[F],
      bureau: CreditBureau[F],
      plaid: Plaid[F],
      engine: ScoringEngine[F]
  ): UnderwritingService[F] = new UnderwritingService[F] {

    def underwrite(
        applicationId: ApplicationId
    ): F[Either[Error, ScoringResult]] =
      applications.find(applicationId).flatMap {
        case None      => Concurrent[F].pure(Left(Error.ApplicationNotFound))
        case Some(app) =>
          businesses.find(app.businessId).flatMap {
            case None      => Concurrent[F].pure(Left(Error.BusinessNotFound))
            case Some(biz) =>
              users.find(biz.ownerUserId).flatMap {
                case None       => Concurrent[F].pure(Left(Error.BusinessNotFound))
                case Some(user) =>
                  user.ssnHash match {
                    case None       => Concurrent[F].pure(Left(Error.KycMissing))
                    case Some(ssnH) =>
                      businesses.linkedAccountsFor(biz.id).flatMap {
                        case Nil =>
                          Concurrent[F].pure(Left(Error.NoLinkedAccount))
                        case linked =>
                          for {
                            _ <- applications.updateStatus(
                              applicationId,
                              ApplicationStatus.Underwriting
                            )
                            bureauRpt <- bureau.report(ssnH)
                            flow <- plaid.cashflowSnapshot(
                              linked.head.plaidItemId,
                              windowDays = 90
                            )
                            inputs = ScoringInputs(
                              bureauScore = Some(bureauRpt.score),
                              monthsInBusiness = monthsBetween(
                                biz.foundedOn,
                                java.time.LocalDate.now()
                              ),
                              monthlyRevenueMinor = biz.monthlyRevenueMinor.value,
                              avgBalanceMinor = flow.avgBalanceMinor,
                              nsfCount12m = flow.nsfCount,
                              industry = biz.industry
                            )
                            scored <- engine.score(applicationId, inputs)
                            priced = applyPolicy(scored, app)
                            saved <- applications.saveScore(priced)
                            _ <-
                              if (priced.approve)
                                applications.applyDecision(
                                  applicationId,
                                  saved.id,
                                  priced.recommendedAmount,
                                  priced.recommendedTermMonths,
                                  priced.recommendedAprBps
                                )
                              else
                                applications.decline(
                                  applicationId,
                                  Body.assume(
                                    s"Declined by ${priced.model} (PD ${priced.pdBps.value}bps)"
                                  )
                                )
                          } yield Right(saved)
                      }
                  }
              }
          }
      }
  }

  /** Cap the model's recommendation to what the applicant asked for; the applicant can lower the offer later but not
    * the lender.
    */
  private def applyPolicy(
      s: ScoringResult,
      app: LoanApplication
  ): ScoringResult = {
    val cappedAmount =
      math.min(s.recommendedAmount.value, app.requestedAmount.value)
    val cappedTerm =
      math.min(s.recommendedTermMonths.value, app.requestedTermMonths.value)
    s.copy(
      recommendedAmount = PositiveAmount.applyUnsafe(cappedAmount),
      recommendedTermMonths = TermMonths.applyUnsafe(cappedTerm)
    )
  }

  private def monthsBetween(
      a: java.time.LocalDate,
      b: java.time.LocalDate
  ): Int =
    java.time.temporal.ChronoUnit.MONTHS.between(a, b).toInt.max(0)
}
