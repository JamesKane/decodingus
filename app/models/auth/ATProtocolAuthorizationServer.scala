package models.auth

import java.time.ZonedDateTime
import java.util.UUID

case class ATProtocolAuthorizationServer(
                                          id: Option[UUID],
                                          issuerUrl: String,
                                          authorizationEndpoint: Option[String],
                                          tokenEndpoint: Option[String],
                                          pushedAuthorizationRequestEndpoint: Option[String],
                                          dpopSigningAlgValuesSupported: Option[String],
                                          scopesSupported: Option[String],
                                          clientIdMetadataDocumentSupported: Option[Boolean],
                                          metadataFetchedAt: ZonedDateTime,
                                          createdAt: ZonedDateTime,
                                          updatedAt: ZonedDateTime
                                        )