package services

import jakarta.inject.{Inject, Singleton}
import models.auth.UserRole
import models.domain.user.User
import play.api.Logging
import repositories.{RoleRepository, UserRepository, UserRoleRepository}

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AuthService @Inject()(
                             atProtocolClient: ATProtocolClient,
                             userRepository: UserRepository,
                             roleRepository: RoleRepository,
                             userRoleRepository: UserRoleRepository,
                             encryptionService: EncryptionService
                           )(implicit ec: ExecutionContext) extends Logging {

  /**
   * Authenticates a user against the PDS and ensures a local User record exists.
   *
   * @param identifier User handle or DID
   * @param password   App Password
   * @return Future[Option[User]] The authenticated user, if successful.
   */
  def login(identifier: String, password: String): Future[Option[User]] = {
    // 1. Authenticate via AT Protocol
    // TODO: Resolve PDS URL instead of hardcoding bsky.social
    atProtocolClient.createSession(identifier, password).flatMap {
      case Some(session) =>
        logger.info(s"AT Protocol session created for ${session.handle} (${session.did})")
        
        // Encrypt the email if present
        val encryptedEmail = session.email.flatMap(encryptionService.encrypt)

        // 2. Find or Create User
        userRepository.findByDid(session.did).flatMap {
          case Some(user) =>
            // Update handle/email if changed
            val updatedUser = user.copy(
              handle = Some(session.handle),
              emailEncrypted = encryptedEmail.orElse(user.emailEncrypted),
              updatedAt = LocalDateTime.now()
            )
            userRepository.update(updatedUser).map(_ => Some(updatedUser))
            
          case None =>
            // Create new user
            val newUser = User(
              id = Some(UUID.randomUUID()),
              did = session.did,
              handle = Some(session.handle),
              emailEncrypted = encryptedEmail,
              displayName = None,
              createdAt = LocalDateTime.now(),
              updatedAt = LocalDateTime.now(),
              isActive = true
            )
            userRepository.create(newUser).map(Some(_))
        }
        
      case None =>
        logger.warn(s"Failed to authenticate $identifier via AT Protocol")
        Future.successful(None)
    }
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
