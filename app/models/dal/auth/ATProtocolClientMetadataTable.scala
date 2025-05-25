package models.dal.auth

import models.auth.ATProtocolClientMetadata
import models.dal.MyPostgresProfile.api.*
import slick.lifted.ProvenShape

import java.time.ZonedDateTime
import java.util.UUID

class ATProtocolClientMetadataTable(tag: Tag) extends Table[ATProtocolClientMetadata](tag, Some("auth"), "atprotocol_client_metadata") {
  def id = column[UUID]("id", O.PrimaryKey)

  def clientIdUrl = column[String]("client_id_url", O.Unique)

  def clientName = column[Option[String]]("client_name")

  def clientUri = column[Option[String]]("client_uri")

  def logoUri = column[Option[String]]("logo_uri")

  def tosUri = column[Option[String]]("tos_uri")

  def policyUri = column[Option[String]]("policy_uri")

  def redirectUris = column[Option[String]]("redirect_uris")

  def createdAt = column[ZonedDateTime]("created_at")

  def updatedAt = column[ZonedDateTime]("updated_at")

  def * : ProvenShape[ATProtocolClientMetadata] = (
    id.?,
    clientIdUrl,
    clientName,
    clientUri,
    logoUri,
    tosUri,
    policyUri,
    redirectUris,
    createdAt,
    updatedAt
  ).mapTo[ATProtocolClientMetadata]
}