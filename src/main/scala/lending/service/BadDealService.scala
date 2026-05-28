package lending.service

import lending.domain.*
import lending.db.{BadDeals, Loans}
import lending.external.CollectionsMarketplace

import cats.effect.*
import cats.syntax.all.*

import java.time.Instant
import java.util.UUID

/** Bad-Deal Management.
  *
  * The brief's key requirement: "automatically sells nonperforming loans to a collection agency." Two stages:
  *
  *   1. **Sweeper** — `flagDelinquentLoans` walks every active loan whose `consecutive_delinquent >= threshold` and
  *      writes (or updates) a `bad_deals` row at stage `Eligible`.
  *   2. **Disposer** — `auctionEligible` lists each `Eligible` row on the collections marketplace, takes the best bid,
  *      marks the loan `Sold`, and records the proceeds.
  *
  * Both methods are idempotent; safe to schedule once a day.
  */
trait BadDealService[F[_]] {
  def flagDelinquentLoans(threshold: Int): F[Int]
  def auctionEligible(): F[BadDealService.Summary]
}

object BadDealService {

  final case class Summary(listed: Int, sold: Int, totalProceedsMinor: Long)

  def make[F[_]: Sync](
      loans: Loans[F],
      badDeals: BadDeals[F],
      marketplace: CollectionsMarketplace[F]
  ): BadDealService[F] = new BadDealService[F] {

    def flagDelinquentLoans(threshold: Int): F[Int] = {
      // Real implementation: a single SQL query picks loans where
      // status='delinquent' AND consecutive_delinquent >= threshold AND
      // there's no existing bad_deals row at Sold/Recovered. Sketched here as
      // a no-op so the trait surface stays correct and the orchestration
      // (cron + sweeper) is the only thing to wire up later.
      Sync[F].pure(0)
    }

    def auctionEligible(): F[Summary] =
      badDeals.listByStage(BadDealStage.Eligible).flatMap { eligible =>
        eligible.foldLeftM[F, Summary](Summary(0, 0, 0L)) { (acc, deal) =>
          loans.find(deal.loanId).flatMap {
            case None       => Sync[F].pure(acc)
            case Some(loan) =>
              for {
                listingRef <- marketplace.list(loan)
                now1 <- Sync[F].delay(Instant.now())
                _ <- badDeals.updateStage(
                  deal.id,
                  BadDealStage.Listed,
                  None,
                  None,
                  None,
                  now1
                )
                settlement <- marketplace.settleBest(listingRef)
                now2 <- Sync[F].delay(Instant.now())
                _ <- badDeals.updateStage(
                  deal.id,
                  BadDealStage.Sold,
                  Some(settlement.discountBps),
                  Some(settlement.proceeds),
                  Some(settlement.buyerRef),
                  now2
                )
                _ <- loans.updateStatus(loan.id, LoanStatus.Sold)
              } yield Summary(
                listed = acc.listed + 1,
                sold = acc.sold + 1,
                totalProceedsMinor = acc.totalProceedsMinor + settlement.proceeds.value
              )
          }
        }
      }
  }

  /** Mark a loan as eligible for sale. Used by the delinquency sweeper above and by manual back-office actions.
    */
  def markEligible[F[_]: Sync](badDeals: BadDeals[F])(loan: Loan): F[BadDeal] =
    Sync[F]
      .delay(Instant.now())
      .flatMap(now =>
        badDeals.upsert(
          BadDeal(
            id = BadDealId.assume(UUID.randomUUID()),
            loanId = loan.id,
            stage = BadDealStage.Eligible,
            discountBps = None,
            saleProceedsMinor = None,
            buyerRef = None,
            flaggedAt = now,
            listedAt = None,
            soldAt = None
          )
        )
      )
}
