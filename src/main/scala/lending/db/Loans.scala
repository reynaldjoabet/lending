package lending.db

import lending.domain.*

import cats.effect.*
import cats.syntax.all.*
import skunk.*
import skunk.implicits.*
import skunk.codec.all.*

trait Loans[F[_]] {
  def insert(l: Loan, schedule: List[RepaymentSchedule]): F[Loan]
  def find(id: LoanId): F[Option[Loan]]
  def listForBusiness(businessId: BusinessId): F[List[Loan]]
  def updateStatus(id: LoanId, status: LoanStatus): F[Unit]
  def adjustOutstanding(id: LoanId, deltaMinor: Long): F[Unit]
  def bumpDelinquent(id: LoanId): F[Unit]
  def clearDelinquent(id: LoanId): F[Unit]

  // schedule
  def scheduleFor(loanId: LoanId): F[List[RepaymentSchedule]]
  def nextDue(loanId: LoanId): F[Option[RepaymentSchedule]]
  def overdueAsOf(today: java.time.LocalDate): F[List[RepaymentSchedule]]
  def updateRepayment(
      id: RepaymentId,
      status: RepaymentStatus,
      railRef: Option[String],
      capturedAt: Option[java.time.Instant]
  ): F[Unit]
}

object Loans {
  import Codecs.{
    loan as loanC,
    loanId as loanIdC,
    businessId as businessIdC,
    loanStatus as statusC,
    repayment as repC,
    repaymentId as repIdC,
    repaymentStatus as repStatusC
  }

  def make[F[_]: Concurrent](pool: Resource[F, Session[F]]): Loans[F] =
    new Loans[F] {

      def insert(l: Loan, schedule: List[RepaymentSchedule]): F[Loan] =
        pool.use { s =>
          s.transaction.use { _ =>
            for {
              saved <- s.prepare(Q.insertLoan).flatMap(_.unique(l))
              _ <- s
                .prepare(Q.insertRepayment)
                .flatMap(pc => schedule.traverse_(pc.execute))
            } yield saved
          }
        }

      def find(id: LoanId): F[Option[Loan]] =
        pool.use(_.prepare(Q.byId).flatMap(_.option(id)))

      def listForBusiness(businessId: BusinessId): F[List[Loan]] =
        pool.use(
          _.prepare(Q.byBusiness).flatMap(
            _.stream(businessId, 32).compile.toList
          )
        )

      def updateStatus(id: LoanId, status: LoanStatus): F[Unit] =
        pool.use(_.prepare(Q.setStatus).flatMap(_.execute((status, id)))).void

      def adjustOutstanding(id: LoanId, deltaMinor: Long): F[Unit] =
        pool.use(_.prepare(Q.adjust).flatMap(_.execute((deltaMinor, id)))).void

      def bumpDelinquent(id: LoanId): F[Unit] =
        pool.use(_.prepare(Q.bumpDelinquent).flatMap(_.execute(id))).void

      def clearDelinquent(id: LoanId): F[Unit] =
        pool.use(_.prepare(Q.clearDelinquent).flatMap(_.execute(id))).void

      def scheduleFor(loanId: LoanId): F[List[RepaymentSchedule]] =
        pool.use(
          _.prepare(Q.scheduleFor).flatMap(_.stream(loanId, 256).compile.toList)
        )

      def nextDue(loanId: LoanId): F[Option[RepaymentSchedule]] =
        pool.use(_.prepare(Q.nextDue).flatMap(_.option(loanId)))

      def overdueAsOf(today: java.time.LocalDate): F[List[RepaymentSchedule]] =
        pool.use(
          _.prepare(Q.overdue).flatMap(_.stream(today, 256).compile.toList)
        )

      def updateRepayment(
          id: RepaymentId,
          status: RepaymentStatus,
          railRef: Option[String],
          capturedAt: Option[java.time.Instant]
      ): F[Unit] =
        pool
          .use(
            _.prepare(Q.setRepayment).flatMap(
              _.execute(
                (
                  status,
                  railRef,
                  capturedAt.map(_.atOffset(java.time.ZoneOffset.UTC)),
                  id
                )
              )
            )
          )
          .void
    }

  private object Q {

    val insertLoan: Query[Loan, Loan] =
      sql"""INSERT INTO loans (id, application_id, business_id, principal_minor, currency,
                                term_months, apr_bps, status, outstanding_minor,
                                disbursed_at, consecutive_delinquent)
            VALUES $loanC
            RETURNING id, application_id, business_id, principal_minor, currency,
                      term_months, apr_bps, status, outstanding_minor,
                      disbursed_at, consecutive_delinquent""".query(loanC)

    val byId: Query[LoanId, Loan] =
      sql"""SELECT id, application_id, business_id, principal_minor, currency,
                   term_months, apr_bps, status, outstanding_minor,
                   disbursed_at, consecutive_delinquent
            FROM loans WHERE id = $loanIdC""".query(loanC)

    val byBusiness: Query[BusinessId, Loan] =
      sql"""SELECT id, application_id, business_id, principal_minor, currency,
                   term_months, apr_bps, status, outstanding_minor,
                   disbursed_at, consecutive_delinquent
            FROM loans WHERE business_id = $businessIdC""".query(loanC)

    val setStatus: Command[(LoanStatus, LoanId)] =
      sql"UPDATE loans SET status = $statusC WHERE id = $loanIdC".command

    val adjust: Command[(Long, LoanId)] =
      sql"UPDATE loans SET outstanding_minor = outstanding_minor + $int8 WHERE id = $loanIdC".command

    val bumpDelinquent: Command[LoanId] =
      sql"UPDATE loans SET consecutive_delinquent = consecutive_delinquent + 1 WHERE id = $loanIdC".command

    val clearDelinquent: Command[LoanId] =
      sql"UPDATE loans SET consecutive_delinquent = 0 WHERE id = $loanIdC".command

    val insertRepayment: Command[RepaymentSchedule] =
      sql"""INSERT INTO repayments (id, loan_id, sequence, due_on, principal_minor, interest_minor,
                                     total_minor, status, captured_at, rail_ref)
            VALUES $repC""".command

    val scheduleFor: Query[LoanId, RepaymentSchedule] =
      sql"""SELECT id, loan_id, sequence, due_on, principal_minor, interest_minor,
                   total_minor, status, captured_at, rail_ref
            FROM repayments WHERE loan_id = $loanIdC ORDER BY sequence""".query(
        repC
      )

    val nextDue: Query[LoanId, RepaymentSchedule] =
      sql"""SELECT id, loan_id, sequence, due_on, principal_minor, interest_minor,
                   total_minor, status, captured_at, rail_ref
            FROM repayments WHERE loan_id = $loanIdC AND status = 'scheduled'
            ORDER BY due_on LIMIT 1""".query(repC)

    val overdue: Query[java.time.LocalDate, RepaymentSchedule] =
      sql"""SELECT id, loan_id, sequence, due_on, principal_minor, interest_minor,
                   total_minor, status, captured_at, rail_ref
            FROM repayments WHERE status = 'scheduled' AND due_on < $date"""
        .query(repC)

    val setRepayment: Command[
      (
          RepaymentStatus,
          Option[String],
          Option[java.time.OffsetDateTime],
          RepaymentId
      )
    ] =
      sql"""UPDATE repayments SET status = $repStatusC, rail_ref = ${varchar(
          128
        ).opt},
                                   captured_at = ${timestamptz.opt}
            WHERE id = $repIdC""".command
  }
}
