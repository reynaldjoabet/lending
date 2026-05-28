package lending.service

import lending.domain.*
import lending.db.Users

import cats.effect.*
import cats.syntax.all.*
import com.password4j.Password

import java.time.Instant
import java.util.UUID

trait Auth[F[_]] {
  def signup(
      email: Email,
      phone: PhoneE164,
      password: String,
      fullName: FullName
  ): F[Either[Auth.Error, User]]
  def login(email: Email, password: String): F[Either[Auth.Error, User]]
}

object Auth {
  sealed trait Error
  object Error {
    case object EmailTaken extends Error
    case object PasswordTooWeak extends Error
    case object InvalidCredentials extends Error
  }

  def make[F[_]: Sync](users: Users[F]): Auth[F] = new Auth[F] {
    def signup(
        email: Email,
        phone: PhoneE164,
        password: String,
        fullName: FullName
    ): F[Either[Error, User]] =
      validatePassword(password) match {
        case Left(err) => Sync[F].pure(Left(err))
        case Right(_)  =>
          users.findByEmail(email).flatMap {
            case Some(_) => Sync[F].pure(Left(Error.EmailTaken))
            case None    =>
              for {
                hash <- Sync[F].delay(
                  Password.hash(password).withBcrypt().getResult
                )
                now <- Sync[F].delay(Instant.now())
                u = User(
                  UserId.assume(UUID.randomUUID()),
                  email,
                  phone,
                  PasswordHash.assume(hash),
                  fullName,
                  Role.Applicant,
                  None,
                  None,
                  None,
                  KycStatus.NotStarted,
                  now
                )
                saved <- users.create(u)
              } yield Right(saved)
          }
      }

    def login(email: Email, password: String): F[Either[Error, User]] =
      users.findByEmail(email).map {
        case Some(u) if Password.check(password, u.passwordHash.value).withBcrypt() =>
          Right(u)
        case _ => Left(Error.InvalidCredentials)
      }
  }

  private def validatePassword(p: String): Either[Error, Unit] =
    if (p.length < 8 || !p.exists(_.isDigit) || !p.exists(_.isLetter))
      Left(Error.PasswordTooWeak)
    else Right(())
}
