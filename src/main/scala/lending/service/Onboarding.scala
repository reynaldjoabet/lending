package lending.service

import lending.domain.*
import lending.db.{Businesses, Users}
import lending.external.{CreditBureau, Kyc, Plaid}

import cats.effect.*
import cats.syntax.all.*

import java.security.MessageDigest
import java.time.{Instant, LocalDate}
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

trait Onboarding[F[_]] {
  def submitKyc(
      userId: UserId,
      fullName: FullName,
      dob: LocalDate,
      ssn: Ssn,
      documentImageUrl: String,
      selfieUrl: String
  ): F[KycStatus]
  def registerBusiness(
      ownerUserId: UserId,
      name: BusinessName,
      ein: Ein,
      industry: String,
      foundedOn: LocalDate,
      annualRevenue: PositiveAmount,
      monthlyRevenue: PositiveAmount
  ): F[Business]
  def linkBankAccount(
      businessId: BusinessId,
      plaidPublicToken: String
  ): F[LinkedAccount]
}

object Onboarding {

  final case class SsnPepper(value: Array[Byte])

  def derivePepper(seed: String): SsnPepper =
    SsnPepper(
      MessageDigest.getInstance("SHA-256").digest(seed.getBytes("UTF-8"))
    )

  def make[F[_]: Sync](
      users: Users[F],
      businesses: Businesses[F],
      kyc: Kyc[F],
      bureau: CreditBureau[F],
      plaid: Plaid[F],
      pepper: SsnPepper
  ): Onboarding[F] = new Onboarding[F] {

    def submitKyc(
        userId: UserId,
        fullName: FullName,
        dob: LocalDate,
        ssn: Ssn,
        documentImageUrl: String,
        selfieUrl: String
    ): F[KycStatus] =
      for {
        hash <- Sync[F].delay(hmacHex(pepper.value, ssn.value))
        ssnH = SsnHash.assume(hash)
        ssnL4 = SsnLast4.assume(ssn.value.takeRight(4))
        decision <- kyc.submit(
          userId,
          fullName,
          dob,
          ssn,
          documentImageUrl,
          selfieUrl
        )
        status = decision match {
          case Kyc.Decision.Approved    => KycStatus.Approved
          case Kyc.Decision.Rejected(_) => KycStatus.Rejected
          case Kyc.Decision.Pending(_)  => KycStatus.Pending
        }
        _ <- users.updateKyc(userId, status, Some(ssnH), Some(ssnL4), Some(dob))
        // Warm the bureau cache for the upcoming application; non-blocking on failure.
        _ <-
          if (status == KycStatus.Approved) bureau.report(ssnH).attempt.void
          else Sync[F].unit
      } yield status

    def registerBusiness(
        ownerUserId: UserId,
        name: BusinessName,
        ein: Ein,
        industry: String,
        foundedOn: LocalDate,
        annualRevenue: PositiveAmount,
        monthlyRevenue: PositiveAmount
    ): F[Business] =
      for {
        now <- Sync[F].delay(Instant.now())
        b = Business(
          BusinessId.assume(UUID.randomUUID()),
          ownerUserId,
          name,
          ein,
          industry,
          foundedOn,
          annualRevenue,
          monthlyRevenue,
          now
        )
        saved <- businesses.create(b)
      } yield saved

    def linkBankAccount(
        businessId: BusinessId,
        plaidPublicToken: String
    ): F[LinkedAccount] =
      for {
        link <- plaid.exchangePublicToken(plaidPublicToken)
        now <- Sync[F].delay(Instant.now())
        la = LinkedAccount(
          LinkedAccountId.assume(UUID.randomUUID()),
          businessId,
          link.itemId,
          link.routing,
          link.last4,
          link.holderName,
          now
        )
        saved <- businesses.addLinkedAccount(la)
      } yield saved
  }

  private def hmacHex(key: Array[Byte], data: String): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(data.getBytes("UTF-8")).map(b => f"$b%02x").mkString
  }
}
