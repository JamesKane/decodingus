package models

import java.time.ZonedDateTime
import java.util.UUID

case class UserLoginInfo(
                          id: Option[UUID],
                          userId: UUID,
                          providerId: String,
                          providerKey: String,
                          createdAt: ZonedDateTime,
                          updatedAt: ZonedDateTime
                        )