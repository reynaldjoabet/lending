package lending.db

import lending.domain.*

import cats.effect.*
import cats.syntax.all.*
import skunk.*
import skunk.implicits.*

trait Cards[F[_]] {
  def insert(c: VirtualCard): F[VirtualCard]
  def findForLoan(loanId: LoanId): F[Option[VirtualCard]]
  def setStatus(id: CardId, status: CardStatus): F[Unit]
}

object Cards {
  import Codecs.{virtualCard as cardC, cardId as cardIdC, loanId as loanIdC, cardStatus as statusC}

  def make[F[_]: Concurrent](pool: Resource[F, Session[F]]): Cards[F] =
    new Cards[F] {
      def insert(c: VirtualCard): F[VirtualCard] =
        pool.use(_.prepare(Q.insert).flatMap(_.unique(c)))
      def findForLoan(loanId: LoanId): F[Option[VirtualCard]] =
        pool.use(_.prepare(Q.byLoan).flatMap(_.option(loanId)))
      def setStatus(id: CardId, status: CardStatus): F[Unit] =
        pool.use(_.prepare(Q.setStatus).flatMap(_.execute((status, id)))).void
    }

  private object Q {
    val insert: Query[VirtualCard, VirtualCard] =
      sql"""INSERT INTO virtual_cards (id, loan_id, token, last4, expiry, status, spend_limit, issued_at)
            VALUES $cardC
            RETURNING id, loan_id, token, last4, expiry, status, spend_limit, issued_at"""
        .query(cardC)

    val byLoan: Query[LoanId, VirtualCard] =
      sql"""SELECT id, loan_id, token, last4, expiry, status, spend_limit, issued_at
            FROM virtual_cards WHERE loan_id = $loanIdC ORDER BY issued_at DESC LIMIT 1"""
        .query(cardC)

    val setStatus: Command[(CardStatus, CardId)] =
      sql"UPDATE virtual_cards SET status = $statusC WHERE id = $cardIdC".command
  }
}
