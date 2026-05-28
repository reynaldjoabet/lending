package lending.http

import lending.domain.*
import lending.db.*
import lending.service.*

import cats.effect.*
import cats.syntax.all.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.Http4sDsl

import java.time.Instant
import java.util.UUID

final class Routes[F[_]: Concurrent](
    auth: Auth[F],
    onboarding: Onboarding[F],
    underwriting: UnderwritingService[F],
    lifecycle: LoanLifecycle[F],
    badDeals: BadDealService[F],
    users: Users[F],
    businesses: Businesses[F],
    applications: Applications[F],
    loans: Loans[F]
) extends Http4sDsl[F] {

  import Json.given

  private object Principal {
    def unapply(req: Request[F]): Option[UserId] =
      req.headers
        .get(org.typelevel.ci.CIString("X-User-Id"))
        .flatMap(h =>
          scala.util
            .Try(UUID.fromString(h.head.value))
            .toOption
            .map(UserId.assume)
        )
  }

  private object AppVar {
    def unapply(s: String): Option[ApplicationId] =
      uuidVar(s, ApplicationId.assume)
  }
  private object BizVar {
    def unapply(s: String): Option[BusinessId] = uuidVar(s, BusinessId.assume)
  }
  private object LoanVar {
    def unapply(s: String): Option[LoanId] = uuidVar(s, LoanId.assume)
  }

  private def uuidVar[A](s: String, f: UUID => A): Option[A] =
    scala.util.Try(UUID.fromString(s)).toOption.map(f)

  private def authed(
      req: Request[F]
  )(body: UserId => F[Response[F]]): F[Response[F]] =
    Principal.unapply(req) match {
      case None      => Forbidden()
      case Some(uid) => body(uid)
    }

  private def adminGate(
      req: Request[F]
  )(body: User => F[Response[F]]): F[Response[F]] =
    Principal.unapply(req) match {
      case None      => Forbidden()
      case Some(uid) =>
        users.find(uid).flatMap {
          case Some(u) if u.role == Role.Admin || u.role == Role.BackOffice =>
            body(u)
          case _ => Forbidden()
        }
    }

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {

    case GET -> Root / "health" =>
      Ok(Map("status" -> "ok").asJson)

    // ---- auth ----

    case req @ POST -> Root / "auth" / "signup" =>
      req
        .as[Json.SignupBody]
        .flatMap(b =>
          auth.signup(b.email, b.phone, b.password, b.fullName).flatMap {
            case Right(u) => Created(u)
            case Left(e)  => BadRequest(Map("error" -> e.toString).asJson)
          }
        )

    case req @ POST -> Root / "auth" / "login" =>
      req
        .as[Json.LoginBody]
        .flatMap(b =>
          auth.login(b.email, b.password).flatMap {
            case Right(u) => Ok(u)
            case Left(_)  => Forbidden(Map("error" -> "invalid").asJson)
          }
        )

    // ---- onboarding ----

    case req @ POST -> Root / "kyc" =>
      authed(req)(uid =>
        req
          .as[Json.KycSubmitBody]
          .flatMap(b =>
            onboarding
              .submitKyc(
                uid,
                b.fullName,
                b.dateOfBirth,
                b.ssn,
                b.documentImageUrl,
                b.selfieUrl
              )
              .flatMap(s => Accepted(Map("status" -> s.toString.toLowerCase).asJson))
          )
      )

    case req @ POST -> Root / "businesses" =>
      authed(req)(uid =>
        req
          .as[Json.RegisterBusinessBody]
          .flatMap(b =>
            onboarding
              .registerBusiness(
                uid,
                b.name,
                b.ein,
                b.industry,
                b.foundedOn,
                b.annualRevenue,
                b.monthlyRevenue
              )
              .flatMap(Created(_))
          )
      )

    case req @ GET -> Root / "businesses" =>
      authed(req)(uid => businesses.listForOwner(uid).flatMap(Ok(_)))

    case req @ POST -> Root / "businesses" / BizVar(bid) / "link-bank" =>
      authed(req)(_ =>
        req
          .as[Json.LinkBankBody]
          .flatMap(b =>
            onboarding
              .linkBankAccount(bid, b.plaidPublicToken)
              .flatMap(Created(_))
          )
      )

    // ---- applications ----

    case req @ POST -> Root / "applications" =>
      authed(req)(_ =>
        req.as[Json.ApplyBody].flatMap { b =>
          val a = LoanApplication(
            id = ApplicationId.assume(UUID.randomUUID()),
            businessId = b.businessId,
            requestedAmount = b.requestedAmount,
            currency = b.currency,
            requestedTermMonths = b.termMonths,
            purpose = b.purpose,
            status = ApplicationStatus.Submitted,
            scoreId = None,
            pricedAmount = None,
            pricedTermMonths = None,
            pricedAprBps = None,
            declinedReason = None,
            submittedAt = Instant.now(),
            decidedAt = None
          )
          applications.submit(a).flatMap(Created(_))
        }
      )

    case req @ POST -> Root / "applications" / AppVar(aid) / "underwrite" =>
      authed(req)(_ =>
        underwriting.underwrite(aid).flatMap {
          case Right(s) => Ok(s)
          case Left(e)  => BadRequest(Map("error" -> e.toString).asJson)
        }
      )

    case req @ POST -> Root / "applications" / AppVar(aid) / "send-agreement" =>
      authed(req)(_ =>
        lifecycle.sendAgreement(aid).flatMap {
          case Right(a) => Accepted(a)
          case Left(e)  => BadRequest(Map("error" -> e.toString).asJson)
        }
      )

    // DocuSign webhook (no auth — verify signature in production middleware).
    case req @ POST -> Root / "webhooks" / "docusign" =>
      req.as[String].flatMap { _ =>
        // Real impl: docusign.decodeWebhook -> dispatch handleSigned.
        Accepted()
      }

    case req @ GET -> Root / "applications" =>
      authed(req)(uid =>
        for {
          bizs <- businesses.listForOwner(uid)
          apps <- bizs.flatTraverse(b => applications.listForBusiness(b.id))
          r <- Ok(apps)
        } yield r
      )

    // ---- loans ----

    case req @ GET -> Root / "loans" =>
      authed(req)(uid =>
        for {
          bizs <- businesses.listForOwner(uid)
          ls <- bizs.flatTraverse(b => loans.listForBusiness(b.id))
          r <- Ok(ls)
        } yield r
      )

    case req @ GET -> Root / "loans" / LoanVar(lid) / "schedule" =>
      authed(req)(_ => loans.scheduleFor(lid).flatMap(Ok(_)))

    // ---- admin / back-office ----

    case req @ POST -> Root / "admin" / "bad-deals" / "auction" =>
      adminGate(req)(_ =>
        badDeals
          .auctionEligible()
          .flatMap(s =>
            Ok(
              Map[String, Long](
                "listed" -> s.listed.toLong,
                "sold" -> s.sold.toLong,
                "proceedsMinor" -> s.totalProceedsMinor
              ).asJson
            )
          )
      )

    case req @ POST -> Root / "admin" / "bad-deals" / "sweep" =>
      adminGate(req)(_ =>
        badDeals
          .flagDelinquentLoans(threshold = 3)
          .flatMap(n => Ok(Map("flagged" -> n).asJson))
      )
  }
}
