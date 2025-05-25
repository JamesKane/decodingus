package models

import java.time.ZonedDateTime
import java.util.UUID

case class ATProtocolClientMetadata(
                                     id: Option[UUID],
                                     clientIdUrl: String,
                                     clientName: Option[String],
                                     clientUri: Option[String],
                                     logoUri: Option[String],
                                     tosUri: Option[String],
                                     policyUri: Option[String],
                                     redirectUris: Option[String],
                                     createdAt: ZonedDateTime,
                                     updatedAt: ZonedDateTime
                                   )