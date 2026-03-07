package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.domain.ibd.PopulationOverlapScore
import play.api.Logging
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import java.time.ZonedDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

trait PopulationOverlapScoreRepository {
  def upsert(score: PopulationOverlapScore): Future[PopulationOverlapScore]
  def findByPair(guid1: UUID, guid2: UUID): Future[Option[PopulationOverlapScore]]
  def findBySample(sampleGuid: UUID, minScore: Double): Future[Seq[PopulationOverlapScore]]
  def deleteAll(): Future[Int]
}

@Singleton
class PopulationOverlapScoreRepositoryImpl @Inject()(
  protected val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[JdbcProfile]
    with PopulationOverlapScoreRepository
    with Logging {

  import profile.api.*

  private val scores = DatabaseSchema.domain.ibd.populationOverlapScores

  private def ordered(g1: UUID, g2: UUID): (UUID, UUID) =
    if (g1.compareTo(g2) < 0) (g1, g2) else (g2, g1)

  override def upsert(score: PopulationOverlapScore): Future[PopulationOverlapScore] = {
    val (g1, g2) = ordered(score.sampleGuid1, score.sampleGuid2)
    val normalized = score.copy(sampleGuid1 = g1, sampleGuid2 = g2)
    findByPair(g1, g2).flatMap {
      case Some(existing) =>
        db.run(
          scores.filter(_.id === existing.id.get)
            .map(s => (s.overlapScore, s.computedAt))
            .update((normalized.overlapScore, ZonedDateTime.now()))
        ).map(_ => normalized.copy(id = existing.id))
      case None =>
        db.run((scores returning scores.map(_.id) into ((s, id) => s.copy(id = Some(id)))) += normalized)
    }
  }

  override def findByPair(guid1: UUID, guid2: UUID): Future[Option[PopulationOverlapScore]] = {
    val (g1, g2) = ordered(guid1, guid2)
    db.run(scores.filter(s => s.sampleGuid1 === g1 && s.sampleGuid2 === g2).result.headOption)
  }

  override def findBySample(sampleGuid: UUID, minScore: Double): Future[Seq[PopulationOverlapScore]] =
    db.run(
      scores.filter(s =>
        (s.sampleGuid1 === sampleGuid || s.sampleGuid2 === sampleGuid) && s.overlapScore >= minScore
      ).sortBy(_.overlapScore.desc).result
    )

  override def deleteAll(): Future[Int] =
    db.run(scores.delete)
}
