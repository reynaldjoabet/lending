package lending.domain

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

import java.util.UUID

// ---------- Identifiers ----------

type UserId = UserId.T
object UserId extends RefinedType[UUID, Pure] {}

type BusinessId = BusinessId.T
object BusinessId extends RefinedType[UUID, Pure] {}

type ApplicationId = ApplicationId.T
object ApplicationId extends RefinedType[UUID, Pure] {}

type ScoreId = ScoreId.T
object ScoreId extends RefinedType[UUID, Pure] {}

type LoanId = LoanId.T
object LoanId extends RefinedType[UUID, Pure] {}

type AgreementId = AgreementId.T
object AgreementId extends RefinedType[UUID, Pure] {}

type RepaymentId = RepaymentId.T
object RepaymentId extends RefinedType[UUID, Pure] {}

type LinkedAccountId = LinkedAccountId.T
object LinkedAccountId extends RefinedType[UUID, Pure] {}

type CardId = CardId.T
object CardId extends RefinedType[UUID, Pure] {}

type BadDealId = BadDealId.T
object BadDealId extends RefinedType[UUID, Pure] {}

// ---------- Contact ----------

type EmailConstraint = Not[Blank] & MaxLength[254] & Match["""^[^@\s]+@[^@\s]+\.[^@\s]+$"""]
type Email = Email.T
object Email extends RefinedType[String, EmailConstraint] {}

type PhoneE164 = PhoneE164.T
object PhoneE164 extends RefinedType[String, Match["""^\+[1-9][0-9]{7,14}$"""]] {}

type FullName = FullName.T
object FullName extends RefinedType[String, Not[Blank] & MaxLength[200]] {}

type PasswordHash = PasswordHash.T
object PasswordHash extends RefinedType[String, Not[Blank] & MaxLength[120]] {}

// ---------- Business identifiers ----------

type BusinessName = BusinessName.T
object BusinessName extends RefinedType[String, Not[Blank] & MaxLength[200]] {}

/** US EIN, 9 digits. */
type Ein = Ein.T
object Ein extends RefinedType[String, FixedLength[9] & Match["^[0-9]{9}$"]] {}

// ---------- Sensitive identifiers (never plaintext at rest) ----------

type Ssn = Ssn.T
object Ssn extends RefinedType[String, FixedLength[9] & Match["^[0-9]{9}$"]] {}

type SsnHash = SsnHash.T
object SsnHash extends RefinedType[String, FixedLength[64] & Match["^[0-9a-f]{64}$"]] {}

type SsnLast4 = SsnLast4.T
object SsnLast4 extends RefinedType[String, FixedLength[4] & Match["^[0-9]{4}$"]] {}

// ---------- Money ----------

type AmountMinor = AmountMinor.T
object AmountMinor extends RefinedType[Long, Pure] {}

/** Strictly positive — principal, payment, limit, fee amounts. */
type PositiveAmount = PositiveAmount.T
object PositiveAmount extends RefinedType[Long, Positive] {}

type CurrencyCode = CurrencyCode.T
object CurrencyCode extends RefinedType[String, Match["^[A-Z]{3}$"]] {
  val USD: CurrencyCode = CurrencyCode.applyUnsafe("USD")
}

// ---------- Risk & rate ----------

/** Risk score the scoring engine emits, mapped to a 300..850 FICO-equivalent scale so downstream policy reads the same
  * way regardless of upstream model.
  */
type Score = Score.T
object Score extends RefinedType[Int, GreaterEqual[300] & LessEqual[850]] {}

/** APR in basis points, capped at 100_000 = 1000% to flag anything usurious. */
type AprBps = AprBps.T
object AprBps extends RefinedType[Int, GreaterEqual[0] & LessEqual[100_000]] {}

/** Probability of default, in basis points (0..10_000 = 0%..100%). */
type PdBps = PdBps.T
object PdBps extends RefinedType[Int, GreaterEqual[0] & LessEqual[10_000]] {}

/** Term in months — Iron enforces a sane band so a 0- or 600-month loan can't be modelled.
  */
type TermMonths = TermMonths.T
object TermMonths extends RefinedType[Int, GreaterEqual[1] & LessEqual[120]] {}

// ---------- Cards / banking ----------

type CardToken = CardToken.T
object CardToken extends RefinedType[String, Not[Blank] & MaxLength[128]] {}

type Last4 = Last4.T
object Last4 extends RefinedType[String, FixedLength[4] & Match["^[0-9]{4}$"]] {}

type CardExpiry = CardExpiry.T
object CardExpiry extends RefinedType[String, Match["""^(0[1-9]|1[0-2])/[0-9]{2}$"""]] {}

type RoutingNumber = RoutingNumber.T
object RoutingNumber extends RefinedType[String, FixedLength[9] & Match["^[0-9]{9}$"]] {}

// ---------- Plaid ----------

/** Plaid's `item_id` is opaque, ~30 chars URL-safe. */
type PlaidItemId = PlaidItemId.T
object PlaidItemId extends RefinedType[String, Not[Blank] & MaxLength[64]] {}

// ---------- Docs ----------

type DocuSignEnvelopeId = DocuSignEnvelopeId.T
object DocuSignEnvelopeId extends RefinedType[String, Match["^[A-Za-z0-9-]{36}$"]] {}

type Title = Title.T
object Title extends RefinedType[String, Not[Blank] & MaxLength[200]] {}

type Body = Body.T
object Body extends RefinedType[String, Not[Blank] & MaxLength[8000]] {}

// ---------- Enums ----------

enum Role { case Applicant, Admin, BackOffice }
object Role {
  def parse(s: String): Either[String, Role] = s.toLowerCase match {
    case "applicant"   => Right(Applicant)
    case "admin"       => Right(Admin)
    case "back_office" => Right(BackOffice)
    case o             => Left(s"unknown role: $o")
  }
  def render(r: Role): String = r match {
    case Applicant  => "applicant"
    case Admin      => "admin"
    case BackOffice => "back_office"
  }
}

enum KycStatus { case NotStarted, Pending, Approved, Rejected }

enum ApplicationStatus {
  case Submitted // applicant filed the form
  case Underwriting // scoring engine is computing
  case Approved // priced, awaiting agreement
  case Declined
  case Withdrawn
  case AgreementSent // DocuSign envelope dispatched
  case AgreementSigned // applicant signed
  case Disbursed // funds released
}

enum LoanStatus {
  case Active, Delinquent, PaidOff, ChargedOff, Sold
}

enum RepaymentStatus { case Scheduled, Paid, Failed, Skipped }

enum CardStatus { case Issued, Active, Frozen, Cancelled }

enum BadDealStage { case Eligible, Listed, Sold, Recovered }
