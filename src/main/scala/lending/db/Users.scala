package lending.db

import lending.domain.*

import cats.effect.*
import skunk.*
import skunk.implicits.*
import cats.syntax.all.*
trait Users[F[_]] {
  def create(u: User): F[User]
  def find(id: UserId): F[Option[User]]
  def findByEmail(e: Email): F[Option[User]]
  def updateKyc(
      id: UserId,
      status: KycStatus,
      ssnHash: Option[SsnHash],
      ssnLast4: Option[SsnLast4],
      dob: Option[java.time.LocalDate]
  ): F[Unit]
}

object Users {
  import Codecs.{user as userC, userId as userIdC, email as emailC, kycStatus, ssnHash, ssnLast4}
  import skunk.codec.all.date

  def make[F[_]: Concurrent](pool: Resource[F, Session[F]]): Users[F] =
    new Users[F] {
      def create(u: User): F[User] =
        pool.use(_.prepare(Q.insert).flatMap(_.unique(u)))
      def find(id: UserId): F[Option[User]] =
        pool.use(_.prepare(Q.byId).flatMap(_.option(id)))
      def findByEmail(e: Email): F[Option[User]] =
        pool.use(_.prepare(Q.byEmail).flatMap(_.option(e)))
      def updateKyc(
          id: UserId,
          status: KycStatus,
          h: Option[SsnHash],
          l: Option[SsnLast4],
          dob: Option[java.time.LocalDate]
      ): F[Unit] =
        pool
          .use(_.prepare(Q.setKyc).flatMap(_.execute((status, h, l, dob, id))))
          .void
    }

  private object Q {
    val insert: Query[User, User] =
      sql"""INSERT INTO users (id, email, phone, password_hash, full_name, role, ssn_hash, ssn_last4,
                                date_of_birth, kyc_status, created_at)
            VALUES $userC
            RETURNING id, email, phone, password_hash, full_name, role, ssn_hash, ssn_last4,
                      date_of_birth, kyc_status, created_at""".query(userC)
    val byId: Query[UserId, User] =
      sql"""SELECT id, email, phone, password_hash, full_name, role, ssn_hash, ssn_last4,
                   date_of_birth, kyc_status, created_at FROM users WHERE id = $userIdC"""
        .query(userC)
    val byEmail: Query[Email, User] =
      sql"""SELECT id, email, phone, password_hash, full_name, role, ssn_hash, ssn_last4,
                   date_of_birth, kyc_status, created_at FROM users WHERE email = $emailC"""
        .query(userC)
    val setKyc: Command[
      (
          KycStatus,
          Option[SsnHash],
          Option[SsnLast4],
          Option[java.time.LocalDate],
          UserId
      )
    ] =
      sql"""UPDATE users
            SET kyc_status = $kycStatus, ssn_hash = ${ssnHash.opt}, ssn_last4 = ${ssnLast4.opt},
                date_of_birth = ${date.opt}
            WHERE id = $userIdC""".command
  }
}
