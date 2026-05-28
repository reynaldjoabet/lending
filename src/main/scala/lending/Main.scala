package lending

import lending.config.*
import lending.db.*
import lending.external.*
import lending.http.Routes
import lending.service.*
import org.typelevel.otel4s.metrics.Meter.Implicits.noop
import org.typelevel.otel4s.trace.Tracer.Implicits.noop
import cats.effect.*
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import skunk.Session

object Main extends IOApp.Simple {

  def run: IO[Unit] = {
    val logger = Slf4jLogger.getLogger[IO]

    val server: Resource[IO, Unit] =
      for {
        cfg <- Resource.eval(AppConfig.load[IO])

        pool <- Session
          .Builder[IO]
          .pooled(
            max = cfg.db.poolMax
          )

        // ---- repositories ----
        usersR = Users.make[IO](pool)
        bizR = Businesses.make[IO](pool)
        appsR = Applications.make[IO](pool)
        loansR = Loans.make[IO](pool)
        agreeR = Agreements.make[IO](pool)
        cardsR = Cards.make[IO](pool)
        badDealsR = BadDeals.make[IO](pool)

        // ---- external sandboxes ----
        kyc <- Resource.eval(Kyc.sandbox[IO])
        bureau <- Resource.eval(CreditBureau.sandbox[IO])
        plaid <- Resource.eval(Plaid.sandbox[IO])
        engine <- Resource.eval(ScoringEngine.sandbox[IO])
        docusign <- Resource.eval(DocuSign.sandbox[IO])
        mbanq <- Resource.eval(Mbanq.sandbox[IO])
        paypal <- Resource.eval(PayPalRail.sandbox[IO])
        coll <- Resource.eval(CollectionsMarketplace.sandbox[IO])

        // ---- services ----
        pepper = Onboarding.derivePepper(cfg.secrets.ssnPepperSeed)
        authSvc = Auth.make[IO](usersR)
        onboardSvc = Onboarding
          .make[IO](usersR, bizR, kyc, bureau, plaid, pepper)
        underwriting = UnderwritingService
          .make[IO](usersR, bizR, appsR, bureau, plaid, engine)
        lifecycle = LoanLifecycle.make[IO](
          usersR,
          bizR,
          appsR,
          loansR,
          agreeR,
          cardsR,
          docusign,
          mbanq
        )
        badDealSvc = BadDealService.make[IO](loansR, badDealsR, coll)

        routes = new Routes[IO](
          authSvc,
          onboardSvc,
          underwriting,
          lifecycle,
          badDealSvc,
          usersR,
          bizR,
          appsR,
          loansR
        ).routes
        app = Logger.httpApp(logHeaders = true, logBody = false)(
          routes.orNotFound
        )

        host <- Resource.eval(
          IO.fromOption(Host.fromString(cfg.http.host))(
            new IllegalArgumentException("bad host")
          )
        )
        port <- Resource.eval(
          IO.fromOption(Port.fromInt(cfg.http.port))(
            new IllegalArgumentException("bad port")
          )
        )

        _ <- EmberServerBuilder
          .default[IO]
          .withHost(host)
          .withPort(port)
          .withHttpApp(app)
          .build
        _ <- Resource.eval(logger.info(s"Listening on http://$host:$port"))
        _ <- Resource.eval(
          IO.pure(paypal)
        ) // alternative rail; held but not yet wired
      } yield ()

    server.useForever
  }
}
