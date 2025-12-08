package repositories.social

import models.dal.DatabaseSchema
import models.domain.social.ReputationEvent
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.PostgresProfile

import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ReputationEventRepository @Inject()(
                                           protected val dbConfigProvider: DatabaseConfigProvider
                                         )(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[PostgresProfile] {

  import profile.api.*

  private val events = DatabaseSchema.domain.social.reputationEvents

  def create(event: ReputationEvent): Future[ReputationEvent] = {
    db.run((events returning events) += event)
  }

  def findByUserId(userId: UUID): Future[Seq[ReputationEvent]] = {
    db.run(events.filter(_.userId === userId).sortBy(_.createdAt.desc).result)
  }
}
