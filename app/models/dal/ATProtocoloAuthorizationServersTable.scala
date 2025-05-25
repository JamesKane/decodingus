package models.dal

import models.ATProtocolAuthorizationServer
import models.dal.MyPostgresProfile.api.*
import java.time.ZonedDateTime
import java.util.UUID
import slick.lifted.ProvenShape

class ATProtocolAuthorizationServersTable(tag: Tag) extends Table[ATProtocolAuthorizationServer](tag, Some("auth"), "atprotocol_authorization_servers") {
  def id = column[UUID]("id", O.PrimaryKey)

  def issuerUrl = column[String]("issuer_url", O.Unique)

  def authorizationEndpoint = column[Option[String]]("authorization_endpoint")

  def tokenEndpoint = column[Option[String]]("token_endpoint")

  def pushedAuthorizationRequestEndpoint = column[Option[String]]("pushed_authorization_request_endpoint")

  def dpopSigningAlgValuesSupported = column[Option[String]]("dpop_signing_alg_values_supported")

  def scopesSupported = column[Option[String]]("scopes_supported")

  def clientIdMetadataDocumentSupported = column[Option[Boolean]]("client_id_metadata_document_supported")

  def metadataFetchedAt = column[ZonedDateTime]("metadata_fetched_at")

  def createdAt = column[ZonedDateTime]("created_at")

  def updatedAt = column[ZonedDateTime]("updated_at")

  // Projection for the case class
  def * : ProvenShape[ATProtocolAuthorizationServer] = (
    id.?, // Optional for inserts, DB generates UUID
    issuerUrl,
    authorizationEndpoint,
    tokenEndpoint,
    pushedAuthorizationRequestEndpoint,
    dpopSigningAlgValuesSupported,
    scopesSupported,
    clientIdMetadataDocumentSupported,
    metadataFetchedAt,
    createdAt,
    updatedAt
  ).mapTo[ATProtocolAuthorizationServer]
}