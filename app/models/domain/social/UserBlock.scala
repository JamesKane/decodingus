package models.domain.social

import java.time.LocalDateTime

case class UserBlock(
                      blockerDid: String,
                      blockedDid: String,
                      reason: Option[String] = None,
                      createdAt: LocalDateTime = LocalDateTime.now()
                    )
