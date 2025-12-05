package repositories

import models.PDSRegistration
import models.dal.MetadataSchema.pdsRegistrations
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile
import play.db.NamedDatabase

import java.time.ZonedDateTime
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PDSRegistrationRepository @Inject()(
  @NamedDatabase("metadata") protected val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[JdbcProfile] {

  import profile.api._

  def create(pdsRegistration: PDSRegistration): Future[PDSRegistration] = db.run {
    pdsRegistrations += pdsRegistration
  }.map(_ => pdsRegistration)

  def findByDid(did: String): Future[Option[PDSRegistration]] = db.run {
    pdsRegistrations.filter(_.did === did).result.headOption
  }

  def findByHandle(handle: String): Future[Option[PDSRegistration]] = db.run {
    pdsRegistrations.filter(_.handle === handle).result.headOption
  }

  def updateCursor(did: String, lastCommitCid: String, newCursor: Long): Future[Int] = db.run {
    pdsRegistrations.filter(_.did === did)
      .map(reg => (reg.lastCommitCid, reg.cursor, reg.updatedAt))
      .update((Some(lastCommitCid), newCursor, ZonedDateTime.now()))
  }

  def listAll: Future[Seq[PDSRegistration]] = db.run {
    pdsRegistrations.result
  }

  def delete(did: String): Future[Int] = db.run {
    pdsRegistrations.filter(_.did === did).delete
  }
}
