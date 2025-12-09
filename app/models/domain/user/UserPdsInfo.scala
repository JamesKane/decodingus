package models.domain.user

import java.time.LocalDateTime
import java.util.UUID

/**
 * Stores the home PDS information for a user's AT Protocol identity.
 * Lives in auth schema as it's primarily used for authentication.
 *
 * @param id        UUID Primary Key
 * @param userId    Foreign Key to users.id
 * @param pdsUrl    The resolved PDS endpoint URL (e.g., https://bsky.social)
 * @param did       The user's DID (e.g., did:plc:xxx)
 * @param handle    Cached handle for quick lookups (e.g., alice.bsky.social)
 * @param createdAt Record creation timestamp
 * @param updatedAt Record update timestamp
 */
case class UserPdsInfo(
                        id: Option[UUID],
                        userId: UUID,
                        pdsUrl: String,
                        did: String,
                        handle: Option[String] = None,
                        createdAt: LocalDateTime,
                        updatedAt: LocalDateTime
                      )