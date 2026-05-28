package lending.db

import lending.domain.*

import cats.effect.*
import cats.syntax.all.*
import skunk.*
import skunk.implicits.*
import skunk.codec.all.timestamptz

import java.time.{Instant, ZoneOffset}

trait Agreements[F[_]] {
  def create(a: LoanAgreement): F[LoanAgreement]
  def findByApplication(id: ApplicationId): F[Option[LoanAgreement]]
  def findByEnvelope(envelope: DocuSignEnvelopeId): F[Option[LoanAgreement]]
  def markSigned(id: AgreementId, at: Instant): F[Unit]
  def markDeclined(id: AgreementId, at: Instant): F[Unit]
}

object Agreements {
  import Codecs.{loanAgreement as agreementC, agreementId as agreementIdC, applicationId as appIdC, docusignEnvId}

  def make[F[_]: Concurrent](pool: Resource[F, Session[F]]): Agreements[F] =
    new Agreements[F] {

      def create(a: LoanAgreement): F[LoanAgreement] =
        pool.use(_.prepare(Q.insert).flatMap(_.unique(a)))
      def findByApplication(id: ApplicationId): F[Option[LoanAgreement]] =
        pool.use(_.prepare(Q.byApp).flatMap(_.option(id)))
      def findByEnvelope(
          envelope: DocuSignEnvelopeId
      ): F[Option[LoanAgreement]] =
        pool.use(_.prepare(Q.byEnvelope).flatMap(_.option(envelope)))
      def markSigned(id: AgreementId, at: Instant): F[Unit] =
        pool
          .use(
            _.prepare(Q.signed)
              .flatMap(_.execute((at.atOffset(ZoneOffset.UTC), id)))
          )
          .void
      def markDeclined(id: AgreementId, at: Instant): F[Unit] =
        pool
          .use(
            _.prepare(Q.declined)
              .flatMap(_.execute((at.atOffset(ZoneOffset.UTC), id)))
          )
          .void
    }

  private object Q {
    val insert: Query[LoanAgreement, LoanAgreement] =
      sql"""INSERT INTO loan_agreements (id, application_id, docusign_envelope_id, document_url,
                                          sent_at, signed_at, declined_at)
            VALUES $agreementC
            RETURNING id, application_id, docusign_envelope_id, document_url,
                      sent_at, signed_at, declined_at""".query(agreementC)

    val byApp: Query[ApplicationId, LoanAgreement] =
      sql"""SELECT id, application_id, docusign_envelope_id, document_url,
                   sent_at, signed_at, declined_at
            FROM loan_agreements WHERE application_id = $appIdC""".query(
        agreementC
      )

    val byEnvelope: Query[DocuSignEnvelopeId, LoanAgreement] =
      sql"""SELECT id, application_id, docusign_envelope_id, document_url,
                   sent_at, signed_at, declined_at
            FROM loan_agreements WHERE docusign_envelope_id = $docusignEnvId"""
        .query(agreementC)

    val signed: Command[(java.time.OffsetDateTime, AgreementId)] =
      sql"UPDATE loan_agreements SET signed_at = $timestamptz WHERE id = $agreementIdC".command

    val declined: Command[(java.time.OffsetDateTime, AgreementId)] =
      sql"UPDATE loan_agreements SET declined_at = $timestamptz WHERE id = $agreementIdC".command
  }
}
