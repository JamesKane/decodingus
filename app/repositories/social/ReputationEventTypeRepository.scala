package repositories.social

import models.dal.DatabaseSchema
import models.domain.social.ReputationEventType
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.PostgresProfile

import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ReputationEventTypeRepository @Inject()(
                                               protected val dbConfigProvider: DatabaseConfigProvider
                                             )(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[PostgresProfile] {

  import profile.api.*

  private val eventTypes = DatabaseSchema.domain.social.reputationEventTypes

  def findByName(name: String): Future[Option[ReputationEventType]] = {
    db.run(eventTypes.filter(_.name === name).result.headOption)
  }

  def getById(id: UUID): Future[Option[ReputationEventType]] = {
    db.run(eventTypes.filter(_.id === id).result.headOption)
  }

  def create(eventType: ReputationEventType): Future[ReputationEventType] = {
    db.run((eventTypes returning eventTypes) += eventType)
  }
}
