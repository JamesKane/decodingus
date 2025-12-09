package repositories

import jakarta.inject.{Inject, Singleton}
import models.auth.UserRole
import models.dal.DatabaseSchema
import play.api.db.slick.DatabaseConfigProvider

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UserRoleRepository @Inject()(
                                    override protected val dbConfigProvider: DatabaseConfigProvider
                                  )(implicit override protected val ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider) {

  import models.dal.MyPostgresProfile.api.*

  private val userRoles = DatabaseSchema.auth.userRoles
  private val roles = DatabaseSchema.auth.roles

  def assignRole(userId: UUID, roleId: UUID): Future[Int] = {
    db.run(userRoles += UserRole(userId, roleId))
  }

  def getUserRoles(userId: UUID): Future[Seq[String]] = {
    val query = for {
      ur <- userRoles if ur.userId === userId
      r <- roles if r.id === ur.roleId
    } yield r.name

    db.run(query.result)
  }
  
  def hasRole(userId: UUID, roleName: String): Future[Boolean] = {
    val query = for {
      ur <- userRoles if ur.userId === userId
      r <- roles if r.id === ur.roleId && r.name === roleName
    } yield r
    
    db.run(query.exists.result)
  }

  def hasPermission(userId: UUID, permissionName: String): Future[Boolean] = {
    // Check if user has any role that has the given permission
    val rolePermissions = DatabaseSchema.auth.rolePermissionsTable
    val permissions = DatabaseSchema.auth.permissions

    val query = for {
      ur <- userRoles if ur.userId === userId
      rp <- rolePermissions if rp.roleId === ur.roleId
      p <- permissions if p.id === rp.permissionId && p.name === permissionName
    } yield p

    db.run(query.exists.result)
  }
}
