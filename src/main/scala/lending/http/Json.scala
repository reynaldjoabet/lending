package lending.http

import lending.domain.*

import io.circe.*
import io.circe.generic.semiauto.*
import io.github.iltotore.iron.circe.given

object Json {

  given Encoder[Role] = Encoder.encodeString.contramap(Role.render)
  given Decoder[Role] = Decoder.decodeString.emap(Role.parse)
  given Encoder[KycStatus] =
    Encoder.encodeString.contramap(_.toString.toLowerCase)
  given Encoder[ApplicationStatus] = Encoder.encodeString.contramap {
    case ApplicationStatus.Submitted       => "submitted"
    case ApplicationStatus.Underwriting    => "underwriting"
    case ApplicationStatus.Approved        => "approved"
    case ApplicationStatus.Declined        => "declined"
    case ApplicationStatus.Withdrawn       => "withdrawn"
    case ApplicationStatus.AgreementSent   => "agreement_sent"
    case ApplicationStatus.AgreementSigned => "agreement_signed"
    case ApplicationStatus.Disbursed       => "disbursed"
  }
  given Encoder[LoanStatus] = Encoder.encodeString.contramap {
    case LoanStatus.Active     => "active"
    case LoanStatus.Delinquent => "delinquent"
    case LoanStatus.PaidOff    => "paid_off"
    case LoanStatus.ChargedOff => "charged_off"
    case LoanStatus.Sold       => "sold"
  }
  given Encoder[RepaymentStatus] =
    Encoder.encodeString.contramap(_.toString.toLowerCase)
  given Encoder[CardStatus] =
    Encoder.encodeString.contramap(_.toString.toLowerCase)
  given Encoder[BadDealStage] =
    Encoder.encodeString.contramap(_.toString.toLowerCase)

  // User encoder — never serialises password hashes or SSN-related fields beyond last 4.
  given Encoder[User] = Encoder.instance(u =>
    io.circe.Json.obj(
      "id" -> Encoder.encodeString.apply(u.id.value.toString),
      "email" -> Encoder.encodeString.apply(u.email.value),
      "phone" -> Encoder.encodeString.apply(u.phone.value),
      "fullName" -> Encoder.encodeString.apply(u.fullName.value),
      "role" -> Encoder[Role].apply(u.role),
      "kycStatus" -> Encoder[KycStatus].apply(u.kycStatus),
      "ssnLast4" -> u.ssnLast4.fold(io.circe.Json.Null)(s => Encoder.encodeString.apply(s.value)),
      "createdAt" -> Encoder[java.time.Instant].apply(u.createdAt)
    )
  )
  val userEncoder: Encoder[User] = summon[Encoder[User]]

  given Encoder[Business] = deriveEncoder
  given Encoder[LinkedAccount] = deriveEncoder
  given Encoder[ScoringInputs] = deriveEncoder
  given Encoder[ScoringResult] = deriveEncoder
  given Encoder[LoanApplication] = deriveEncoder
  given Encoder[LoanAgreement] = deriveEncoder
  given Encoder[Loan] = deriveEncoder
  given Encoder[RepaymentSchedule] = deriveEncoder
  given Encoder[VirtualCard] = deriveEncoder
  given Encoder[BadDeal] = deriveEncoder

  // ---- request bodies ----

  final case class SignupBody(
      email: Email,
      phone: PhoneE164,
      password: String,
      fullName: FullName
  )
  given Decoder[SignupBody] = deriveDecoder

  final case class LoginBody(email: Email, password: String)
  given Decoder[LoginBody] = deriveDecoder

  final case class KycSubmitBody(
      fullName: FullName,
      dateOfBirth: java.time.LocalDate,
      ssn: Ssn,
      documentImageUrl: String,
      selfieUrl: String
  )
  given Decoder[KycSubmitBody] = deriveDecoder

  final case class RegisterBusinessBody(
      name: BusinessName,
      ein: Ein,
      industry: String,
      foundedOn: java.time.LocalDate,
      annualRevenue: PositiveAmount,
      monthlyRevenue: PositiveAmount
  )
  given Decoder[RegisterBusinessBody] = deriveDecoder

  final case class LinkBankBody(plaidPublicToken: String)
  given Decoder[LinkBankBody] = deriveDecoder

  final case class ApplyBody(
      businessId: BusinessId,
      requestedAmount: PositiveAmount,
      currency: CurrencyCode,
      termMonths: TermMonths,
      purpose: Body
  )
  given Decoder[ApplyBody] = deriveDecoder
}
