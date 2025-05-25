package models.auth

import java.time.ZonedDateTime
import java.util.UUID

case class UserOauth2Info(
                           id: Option[UUID],
                           loginInfoId: UUID,
                           accessToken: String,
                           tokenType: Option[String],
                           expiresIn: Option[Long],
                           refreshToken: Option[String],
                           createdAt: ZonedDateTime,
                           updatedAt: ZonedDateTime,
                           scope: Option[String]
                         )