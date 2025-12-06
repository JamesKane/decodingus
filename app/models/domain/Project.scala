package models.domain

import java.time.LocalDateTime
import java.util.UUID

case class Project(
                    id: Option[Int] = None,
                    projectGuid: UUID,
                    name: String,
                    description: Option[String] = None,
                    ownerDid: String,
                    createdAt: LocalDateTime,
                    updatedAt: LocalDateTime,
                    deleted: Boolean = false,
                    atUri: Option[String] = None,
                    atCid: Option[String] = None
                  )
