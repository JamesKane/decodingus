package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.domain.publications.PublicationSearchConfig
import play.api.db.slick.DatabaseConfigProvider

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

trait PublicationSearchConfigRepository {
  def create(config: PublicationSearchConfig): Future[PublicationSearchConfig]
  def listAll(): Future[Seq[PublicationSearchConfig]]
  def findById(id: Int): Future[Option[PublicationSearchConfig]]
  def updateLastRun(id: Int, timestamp: LocalDateTime): Future[Boolean]
  def getEnabledConfigs(): Future[Seq[PublicationSearchConfig]]
}

@Singleton
class PublicationSearchConfigRepositoryImpl @Inject()(
  override protected val dbConfigProvider: DatabaseConfigProvider
)(implicit override protected val ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with PublicationSearchConfigRepository {

  import models.dal.MyPostgresProfile.api.*

  private val configsTable = DatabaseSchema.domain.publications.publicationSearchConfigs

  override def create(config: PublicationSearchConfig): Future[PublicationSearchConfig] = {
    db.run((configsTable returning configsTable.map(_.id)
      into ((c, id) => c.copy(id = Some(id)))) += config)
  }

  override def listAll(): Future[Seq[PublicationSearchConfig]] = {
    db.run(configsTable.sortBy(_.id.desc).result)
  }

  override def findById(id: Int): Future[Option[PublicationSearchConfig]] = {
    db.run(configsTable.filter(_.id === id).result.headOption)
  }

  override def updateLastRun(id: Int, timestamp: LocalDateTime): Future[Boolean] = {
    db.run(configsTable.filter(_.id === id).map(_.lastRun).update(Some(timestamp)).map(_ > 0))
  }

  override def getEnabledConfigs(): Future[Seq[PublicationSearchConfig]] = {
    db.run(configsTable.filter(_.enabled === true).result)
  }
}
