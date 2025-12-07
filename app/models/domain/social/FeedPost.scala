package models.domain.social

import java.time.LocalDateTime
import java.util.UUID

case class FeedPost(
                     id: UUID = UUID.randomUUID(),
                     authorDid: String,
                     content: String,
                     parentPostId: Option[UUID] = None,
                     rootPostId: Option[UUID] = None,
                     topic: Option[String] = None,
                     authorReputationScore: Int = 0,
                     createdAt: LocalDateTime = LocalDateTime.now(),
                     updatedAt: LocalDateTime = LocalDateTime.now()
                   )
