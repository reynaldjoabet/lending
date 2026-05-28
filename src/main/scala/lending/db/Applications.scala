package lending.db

import lending.domain.*

import cats.effect.*
import cats.syntax.all.*
import skunk.*
import skunk.implicits.*

trait Applications[F[_]] {
  def submit(a: LoanApplication): F[LoanApplication]
  def find(id: ApplicationId): F[Option[LoanApplication]]
  def listForBusiness(businessId: BusinessId): F[List[LoanApplication]]
  def updateStatus(id: ApplicationId, status: ApplicationStatus): F[Unit]
  def applyDecision(
      id: ApplicationId,
      score: ScoreId,
      amount: PositiveAmount,
      term: TermMonths,
      apr: AprBps
  ): F[Unit]
  def decline(id: ApplicationId, reason: Body): F[Unit]

  // scoring
  def saveScore(r: ScoringResult): F[ScoringResult]
  def findScore(id: ScoreId): F[Option[ScoringResult]]
}

object Applications {
  import Codecs.{
    loanApplication as appC,
    applicationId as appIdC,
    businessId as businessIdC,
    applicationStatus as statusC,
    scoreId as scoreIdC,
    scoringResult as scoreC,
    positiveAmount,
    termMonths,
    aprBps,
    body
  }

  def make[F[_]: Concurrent](pool: Resource[F, Session[F]]): Applications[F] =
    new Applications[F] {

      def submit(a: LoanApplication): F[LoanApplication] =
        pool.use(_.prepare(Q.insert).flatMap(_.unique(a)))
      def find(id: ApplicationId): F[Option[LoanApplication]] =
        pool.use(_.prepare(Q.byId).flatMap(_.option(id)))
      def listForBusiness(businessId: BusinessId): F[List[LoanApplication]] =
        pool.use(
          _.prepare(Q.listForBusiness).flatMap(
            _.stream(businessId, 32).compile.toList
          )
        )
      def updateStatus(id: ApplicationId, status: ApplicationStatus): F[Unit] =
        pool.use(_.prepare(Q.setStatus).flatMap(_.execute((status, id)))).void
      def applyDecision(
          id: ApplicationId,
          score: ScoreId,
          amount: PositiveAmount,
          term: TermMonths,
          apr: AprBps
      ): F[Unit] =
        pool
          .use(
            _.prepare(Q.decision)
              .flatMap(_.execute((score, amount, term, apr, id)))
          )
          .void
      def decline(id: ApplicationId, reason: Body): F[Unit] =
        pool.use(_.prepare(Q.declineQ).flatMap(_.execute((reason, id)))).void

      def saveScore(r: ScoringResult): F[ScoringResult] =
        pool.use(_.prepare(Q.insertScore).flatMap(_.unique(r)))
      def findScore(id: ScoreId): F[Option[ScoringResult]] =
        pool.use(_.prepare(Q.scoreById).flatMap(_.option(id)))
    }

  private object Q {

    val insert: Query[LoanApplication, LoanApplication] =
      sql"""INSERT INTO loan_applications (id, business_id, requested_amount_minor, currency, requested_term_months,
                                            purpose, status, score_id, priced_amount_minor, priced_term_months,
                                            priced_apr_bps, declined_reason, submitted_at, decided_at)
            VALUES $appC
            RETURNING id, business_id, requested_amount_minor, currency, requested_term_months,
                      purpose, status, score_id, priced_amount_minor, priced_term_months,
                      priced_apr_bps, declined_reason, submitted_at, decided_at"""
        .query(appC)

    val byId: Query[ApplicationId, LoanApplication] =
      sql"""SELECT id, business_id, requested_amount_minor, currency, requested_term_months,
                   purpose, status, score_id, priced_amount_minor, priced_term_months,
                   priced_apr_bps, declined_reason, submitted_at, decided_at
            FROM loan_applications WHERE id = $appIdC""".query(appC)

    val listForBusiness: Query[BusinessId, LoanApplication] =
      sql"""SELECT id, business_id, requested_amount_minor, currency, requested_term_months,
                   purpose, status, score_id, priced_amount_minor, priced_term_months,
                   priced_apr_bps, declined_reason, submitted_at, decided_at
            FROM loan_applications WHERE business_id = $businessIdC
            ORDER BY submitted_at DESC""".query(appC)

    val setStatus: Command[(ApplicationStatus, ApplicationId)] =
      sql"UPDATE loan_applications SET status = $statusC WHERE id = $appIdC".command

    val decision: Command[
      (ScoreId, PositiveAmount, TermMonths, AprBps, ApplicationId)
    ] =
      sql"""UPDATE loan_applications
            SET status = 'approved',
                score_id = $scoreIdC,
                priced_amount_minor = $positiveAmount,
                priced_term_months = $termMonths,
                priced_apr_bps = $aprBps,
                decided_at = now()
            WHERE id = $appIdC""".command

    val declineQ: Command[(Body, ApplicationId)] =
      sql"""UPDATE loan_applications
            SET status = 'declined', declined_reason = $body, decided_at = now()
            WHERE id = $appIdC""".command

    val insertScore: Query[ScoringResult, ScoringResult] =
      sql"""INSERT INTO scoring_results (id, application_id, model, score, pd_bps, approve,
                                          recommended_amount, recommended_term_months, recommended_apr_bps,
                                          inputs, scored_at)
            VALUES $scoreC
            RETURNING id, application_id, model, score, pd_bps, approve,
                      recommended_amount, recommended_term_months, recommended_apr_bps,
                      inputs, scored_at""".query(scoreC)

    val scoreById: Query[ScoreId, ScoringResult] =
      sql"""SELECT id, application_id, model, score, pd_bps, approve,
                   recommended_amount, recommended_term_months, recommended_apr_bps,
                   inputs, scored_at
            FROM scoring_results WHERE id = $scoreIdC""".query(scoreC)
  }
}
