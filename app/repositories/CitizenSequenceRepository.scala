package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api.*
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository specifically for fetching the next value from the citizen biosample sequence.
 * Extracted to allow for easier testing of dependent services.
 */
trait CitizenSequenceRepository {
  def getNextSequence(): Future[Long]
}

@Singleton
class SlickCitizenSequenceRepository @Inject()(
                                                protected val dbConfigProvider: DatabaseConfigProvider
                                              )(implicit ec: ExecutionContext)
  extends CitizenSequenceRepository
    with HasDatabaseConfigProvider[MyPostgresProfile] {

  override def getNextSequence(): Future[Long] = {
    // Note: 'citizen_biosample_seq' must exist in the Postgres DB
    val query = sql"SELECT nextval('citizen_biosample_seq')".as[Long]
    db.run(query).map(_.head)
  }
}
