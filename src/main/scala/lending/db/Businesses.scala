package lending.db

import lending.domain.*

import cats.effect.*
import cats.syntax.all.*
import skunk.*
import skunk.implicits.*

trait Businesses[F[_]] {
  def create(b: Business): F[Business]
  def find(id: BusinessId): F[Option[Business]]
  def listForOwner(ownerUserId: UserId): F[List[Business]]
  def addLinkedAccount(la: LinkedAccount): F[LinkedAccount]
  def linkedAccountsFor(id: BusinessId): F[List[LinkedAccount]]
}

object Businesses {
  import Codecs.{business as businessC, businessId as businessIdC, linkedAccount as linkedC, userId as userIdC}

  def make[F[_]: Concurrent](pool: Resource[F, Session[F]]): Businesses[F] =
    new Businesses[F] {
      def create(b: Business): F[Business] =
        pool.use(_.prepare(Q.insert).flatMap(_.unique(b)))
      def find(id: BusinessId): F[Option[Business]] =
        pool.use(_.prepare(Q.byId).flatMap(_.option(id)))
      def listForOwner(ownerUserId: UserId): F[List[Business]] =
        pool.use(
          _.prepare(Q.byOwner).flatMap(_.stream(ownerUserId, 16).compile.toList)
        )
      def addLinkedAccount(la: LinkedAccount): F[LinkedAccount] =
        pool.use(_.prepare(Q.insertLinked).flatMap(_.unique(la)))
      def linkedAccountsFor(id: BusinessId): F[List[LinkedAccount]] =
        pool.use(
          _.prepare(Q.linkedFor).flatMap(_.stream(id, 16).compile.toList)
        )
    }

  private object Q {
    val insert: Query[Business, Business] =
      sql"""INSERT INTO businesses (id, owner_user_id, name, ein, industry, founded_on,
                                     annual_revenue_minor, monthly_revenue_minor, created_at)
            VALUES $businessC
            RETURNING id, owner_user_id, name, ein, industry, founded_on,
                      annual_revenue_minor, monthly_revenue_minor, created_at"""
        .query(businessC)

    val byId: Query[BusinessId, Business] =
      sql"""SELECT id, owner_user_id, name, ein, industry, founded_on,
                   annual_revenue_minor, monthly_revenue_minor, created_at
            FROM businesses WHERE id = $businessIdC""".query(businessC)

    val byOwner: Query[UserId, Business] =
      sql"""SELECT id, owner_user_id, name, ein, industry, founded_on,
                   annual_revenue_minor, monthly_revenue_minor, created_at
            FROM businesses WHERE owner_user_id = $userIdC""".query(businessC)

    val insertLinked: Query[LinkedAccount, LinkedAccount] =
      sql"""INSERT INTO linked_accounts (id, business_id, plaid_item_id, routing_number, last4, holder_name, added_at)
            VALUES $linkedC
            RETURNING id, business_id, plaid_item_id, routing_number, last4, holder_name, added_at"""
        .query(linkedC)

    val linkedFor: Query[BusinessId, LinkedAccount] =
      sql"""SELECT id, business_id, plaid_item_id, routing_number, last4, holder_name, added_at
            FROM linked_accounts WHERE business_id = $businessIdC""".query(
        linkedC
      )
  }
}
