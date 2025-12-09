package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.dal.MyPostgresProfile.api.*
import models.domain.user.UserPdsInfo
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UserPdsInfoRepository @Inject()(
  protected val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {

  private val userPdsInfos = DatabaseSchema.auth.userPdsInfos

  def findByUserId(userId: UUID): Future[Option[UserPdsInfo]] = {
    db.run(userPdsInfos.filter(_.userId === userId).result.headOption)
  }

  def findByDid(did: String): Future[Option[UserPdsInfo]] = {
    db.run(userPdsInfos.filter(_.did === did).result.headOption)
  }

  def create(info: UserPdsInfo): Future[UserPdsInfo] = {
    val infoWithId = info.copy(id = Some(info.id.getOrElse(UUID.randomUUID())))
    db.run((userPdsInfos returning userPdsInfos) += infoWithId)
  }

  def upsertByDid(info: UserPdsInfo): Future[UserPdsInfo] = {
    findByDid(info.did).flatMap {
      case Some(existing) =>
        val updated = info.copy(
          id = existing.id,
          createdAt = existing.createdAt,
          updatedAt = LocalDateTime.now()
        )
        db.run(userPdsInfos.filter(_.did === info.did).update(updated)).map(_ => updated)
      case None =>
        create(info.copy(updatedAt = LocalDateTime.now()))
    }
  }

  def update(info: UserPdsInfo): Future[Int] = {
    db.run(userPdsInfos.filter(_.id === info.id).update(info.copy(updatedAt = LocalDateTime.now())))
  }

  def delete(id: UUID): Future[Int] = {
    db.run(userPdsInfos.filter(_.id === id).delete)
  }
}
