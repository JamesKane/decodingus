package repositories.social

import models.dal.DatabaseSchema
import models.domain.social.UserReputationScore
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.PostgresProfile

import java.time.LocalDateTime
import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UserReputationScoreRepository @Inject()(
                                               protected val dbConfigProvider: DatabaseConfigProvider
                                             )(implicit ec: ExecutionContext) extends HasDatabaseConfigProvider[PostgresProfile] {

  import profile.api.*

  private val scores = DatabaseSchema.domain.social.userReputationScores

  def findByUserId(userId: UUID): Future[Option[UserReputationScore]] = {
    db.run(scores.filter(_.userId === userId).result.headOption)
  }

  def upsertScore(userId: UUID, newScore: Long): Future[Int] = {
    val action = scores.insertOrUpdate(UserReputationScore(userId, newScore, LocalDateTime.now()))
    db.run(action)
  }

  def create(score: UserReputationScore): Future[UserReputationScore] = {
    db.run((scores returning scores) += score)
  }
}
