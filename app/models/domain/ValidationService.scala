package models.domain

import java.util.UUID

case class ValidationService(
                              id: Option[Long],
                              guid: UUID,
                              name: String,
                              description: Option[String],
                              trustLevel: Option[String]
                            )