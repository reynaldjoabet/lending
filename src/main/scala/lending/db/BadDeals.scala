package lending.db

import lending.domain.*

import cats.effect.*
import cats.syntax.all.*
import skunk.*
import skunk.implicits.*

trait BadDeals[F[_]] {
  def upsert(d: BadDeal): F[BadDeal]
  def listByStage(stage: BadDealStage): F[List[BadDeal]]
  def findForLoan(loanId: LoanId): F[Option[BadDeal]]
  def updateStage(
      id: BadDealId,
      stage: BadDealStage,
      discountBps: Option[Int],
      proceeds: Option[PositiveAmount],
      buyerRef: Option[String],
      at: java.time.Instant
  ): F[Unit]
}

object BadDeals {
  import Codecs.{badDeal as bdC, badDealId as bdIdC, loanId as loanIdC, badDealStage as stageC, positiveAmount}
  import skunk.codec.all.{int4, timestamptz, varchar}

  def make[F[_]: Concurrent](pool: Resource[F, Session[F]]): BadDeals[F] =
    new BadDeals[F] {

      def upsert(d: BadDeal): F[BadDeal] =
        pool.use(_.prepare(Q.upsert).flatMap(_.unique(d)))

      def listByStage(stage: BadDealStage): F[List[BadDeal]] =
        pool.use(
          _.prepare(Q.byStage).flatMap(_.stream(stage, 64).compile.toList)
        )

      def findForLoan(loanId: LoanId): F[Option[BadDeal]] =
        pool.use(_.prepare(Q.byLoan).flatMap(_.option(loanId)))

      def updateStage(
          id: BadDealId,
          stage: BadDealStage,
          discountBps: Option[Int],
          proceeds: Option[PositiveAmount],
          buyerRef: Option[String],
          at: java.time.Instant
      ): F[Unit] = {
        val ts = at.atOffset(java.time.ZoneOffset.UTC)
        val listed = if (stage == BadDealStage.Listed) Some(ts) else None
        val sold = if (stage == BadDealStage.Sold) Some(ts) else None
        pool
          .use(
            _.prepare(Q.updateStage).flatMap(
              _.execute(
                (stage, discountBps, proceeds, buyerRef, listed, sold, id)
              )
            )
          )
          .void
      }
    }

  private object Q {
    val upsert: Query[BadDeal, BadDeal] =
      sql"""INSERT INTO bad_deals (id, loan_id, stage, discount_bps, sale_proceeds_minor,
                                    buyer_ref, flagged_at, listed_at, sold_at)
            VALUES $bdC
            ON CONFLICT (loan_id) DO UPDATE
              SET stage = EXCLUDED.stage,
                  discount_bps = EXCLUDED.discount_bps,
                  sale_proceeds_minor = EXCLUDED.sale_proceeds_minor,
                  buyer_ref = EXCLUDED.buyer_ref,
                  listed_at = COALESCE(bad_deals.listed_at, EXCLUDED.listed_at),
                  sold_at   = COALESCE(bad_deals.sold_at,   EXCLUDED.sold_at)
            RETURNING id, loan_id, stage, discount_bps, sale_proceeds_minor,
                      buyer_ref, flagged_at, listed_at, sold_at""".query(bdC)

    val byStage: Query[BadDealStage, BadDeal] =
      sql"""SELECT id, loan_id, stage, discount_bps, sale_proceeds_minor,
                   buyer_ref, flagged_at, listed_at, sold_at
            FROM bad_deals WHERE stage = $stageC ORDER BY flagged_at""".query(
        bdC
      )

    val byLoan: Query[LoanId, BadDeal] =
      sql"""SELECT id, loan_id, stage, discount_bps, sale_proceeds_minor,
                   buyer_ref, flagged_at, listed_at, sold_at
            FROM bad_deals WHERE loan_id = $loanIdC""".query(bdC)

    val updateStage: Command[
      (
          BadDealStage,
          Option[Int],
          Option[PositiveAmount],
          Option[String],
          Option[java.time.OffsetDateTime],
          Option[java.time.OffsetDateTime],
          BadDealId
      )
    ] =
      sql"""UPDATE bad_deals
            SET stage = $stageC,
                discount_bps = ${int4.opt},
                sale_proceeds_minor = ${positiveAmount.opt},
                buyer_ref = ${varchar(128).opt},
                listed_at = COALESCE(${timestamptz.opt}, listed_at),
                sold_at   = COALESCE(${timestamptz.opt}, sold_at)
            WHERE id = $bdIdC""".command
  }
}
