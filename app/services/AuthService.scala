package services

import jakarta.inject.{Inject, Singleton}
import models.auth.UserRole
import models.domain.user.{User, UserPdsInfo}
import play.api.Logging
import repositories.{RoleRepository, UserPdsInfoRepository, UserRepository, UserRoleRepository}

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AuthService @Inject()(
                             atProtocolClient: ATProtocolClient,
                             userRepository: UserRepository,
                             userPdsInfoRepository: UserPdsInfoRepository,
                             roleRepository: RoleRepository,
                             userRoleRepository: UserRoleRepository
                           )(implicit ec: ExecutionContext) extends Logging {

  /**
   * Authenticates a user against their PDS and ensures a local User record exists.
   *
   * The authentication flow:
   * 1. If identifier is a DID, resolve it directly to find PDS URL
   * 2. If identifier is a handle, resolve handle → DID → PDS URL
   * 3. Authenticate against the resolved PDS
   * 4. Create or update local User record
   *
   * @param identifier User handle (e.g., "alice.bsky.social") or DID (e.g., "did:plc:xxx")
   * @param password   App Password
   * @return Future[Option[User]] The authenticated user, if successful.
   */
  def login(identifier: String, password: String): Future[Option[User]] = {
    val normalizedIdentifier = identifier.stripPrefix("@").trim

    // Resolve the PDS URL based on identifier type
    val pdsResolution: Future[Option[(String, String)]] = if (normalizedIdentifier.startsWith("did:")) {
      // Direct DID - resolve to PDS
      atProtocolClient.resolveDid(normalizedIdentifier).map {
        case Some(doc) => doc.getPdsEndpoint.map(pds => (normalizedIdentifier, pds))
        case None => None
      }
    } else {
      // Handle - resolve to DID then to PDS
      atProtocolClient.resolveHandleToPds(normalizedIdentifier)
    }

    pdsResolution.flatMap {
      case Some((resolvedDid, pdsUrl)) =>
        logger.info(s"Resolved $normalizedIdentifier to DID $resolvedDid at PDS $pdsUrl")

        // Authenticate against the resolved PDS
        atProtocolClient.createSession(normalizedIdentifier, password, pdsUrl).flatMap {
          case Some(session) =>
            logger.info(s"AT Protocol session created for ${session.handle} (${session.did}) on $pdsUrl")

            // Find or Create User
            userRepository.findByDid(session.did).flatMap {
              case Some(user) =>
                // Update handle/email if changed
                val updatedUser = user.copy(
                  handle = Some(session.handle),
                  email = session.email.orElse(user.email),
                  updatedAt = LocalDateTime.now()
                )
                for {
                  _ <- userRepository.update(updatedUser)
                  _ <- updateUserPdsInfo(user.id.get, session.did, session.handle, pdsUrl)
                } yield Some(updatedUser)

              case None =>
                // Create new user
                val newUser = User(
                  id = Some(UUID.randomUUID()),
                  email = session.email,
                  did = session.did,
                  handle = Some(session.handle),
                  displayName = None,
                  createdAt = LocalDateTime.now(),
                  updatedAt = LocalDateTime.now(),
                  isActive = true
                )
                for {
                  createdUser <- userRepository.create(newUser)
                  _ <- updateUserPdsInfo(createdUser.id.get, session.did, session.handle, pdsUrl)
                } yield Some(createdUser)
            }

          case None =>
            logger.warn(s"Failed to authenticate $normalizedIdentifier at PDS $pdsUrl")
            Future.successful(None)
        }

      case None =>
        logger.warn(s"Failed to resolve PDS for identifier: $normalizedIdentifier")
        Future.successful(None)
    }
  }

  /**
   * Updates or creates the UserPdsInfo record for a user.
   */
  private def updateUserPdsInfo(userId: UUID, did: String, handle: String, pdsUrl: String): Future[UserPdsInfo] = {
    val now = LocalDateTime.now()
    val info = UserPdsInfo(
      id = None,
      userId = userId,
      pdsUrl = pdsUrl,
      did = did,
      handle = Some(handle),
      createdAt = now,
      updatedAt = now
    )
    userPdsInfoRepository.upsertByDid(info)
  }

  /**
   * Checks if a user has a specific role.
   */
  def hasRole(userId: UUID, roleName: String): Future[Boolean] = {
    userRoleRepository.hasRole(userId, roleName)
  }
  
  /**
   * Checks if a user has any of the provided roles.
   */
  def hasAnyRole(userId: UUID, roleNames: Seq[String]): Future[Boolean] = {
    // This is not efficient if checking many roles individually, but fine for a few.
    // Better to fetch all user roles and check intersection.
    userRoleRepository.getUserRoles(userId).map { userRoles =>
      roleNames.exists(requiredRole => userRoles.contains(requiredRole))
    }
  }

  /**
   * Checks if a user has a specific permission.
   */
  def hasPermission(userId: UUID, permissionName: String): Future[Boolean] = {
    userRoleRepository.hasPermission(userId, permissionName)
  }
}
