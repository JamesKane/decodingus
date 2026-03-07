package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.domain.ibd.MatchSuggestion
import play.api.Logging
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import java.time.ZonedDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

trait MatchSuggestionRepository {
  def create(suggestion: MatchSuggestion): Future[MatchSuggestion]
  def createBatch(suggestions: Seq[MatchSuggestion]): Future[Seq[MatchSuggestion]]
  def findByTargetSample(sampleGuid: UUID, suggestionType: Option[String], limit: Int): Future[Seq[MatchSuggestion]]
  def findById(id: Long): Future[Option[MatchSuggestion]]
  def dismiss(id: Long): Future[Boolean]
  def expireOld(now: ZonedDateTime): Future[Int]
  def countByTargetSample(sampleGuid: UUID): Future[Int]
}

@Singleton
class MatchSuggestionRepositoryImpl @Inject()(
  protected val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[JdbcProfile]
    with MatchSuggestionRepository
    with Logging {

  import profile.api.*
  import models.dal.MyPostgresProfile.api.playJsonTypeMapper

  private val suggestions = DatabaseSchema.domain.ibd.matchSuggestions

  override def create(suggestion: MatchSuggestion): Future[MatchSuggestion] =
    db.run((suggestions returning suggestions.map(_.id) into ((s, id) => s.copy(id = Some(id)))) += suggestion)

  override def createBatch(batch: Seq[MatchSuggestion]): Future[Seq[MatchSuggestion]] =
    db.run((suggestions returning suggestions.map(_.id) into ((s, id) => s.copy(id = Some(id)))) ++= batch).map(_.toSeq)

  override def findByTargetSample(sampleGuid: UUID, suggestionType: Option[String], limit: Int): Future[Seq[MatchSuggestion]] = {
    val query = suggestions
      .filter(s => s.targetSampleGuid === sampleGuid && s.status === "ACTIVE")
    val filtered = suggestionType match {
      case Some(t) => query.filter(_.suggestionType === t)
      case None => query
    }
    db.run(filtered.sortBy(_.score.desc).take(limit).result)
  }

  override def findById(id: Long): Future[Option[MatchSuggestion]] =
    db.run(suggestions.filter(_.id === id).result.headOption)

  override def dismiss(id: Long): Future[Boolean] =
    db.run(
      suggestions.filter(s => s.id === id && s.status === "ACTIVE")
        .map(_.status)
        .update("DISMISSED")
    ).map(_ > 0)

  override def expireOld(now: ZonedDateTime): Future[Int] =
    db.run(
      suggestions.filter(s => s.status === "ACTIVE" && s.expiresAt <= now)
        .map(_.status)
        .update("EXPIRED")
    )

  override def countByTargetSample(sampleGuid: UUID): Future[Int] =
    db.run(suggestions.filter(s => s.targetSampleGuid === sampleGuid && s.status === "ACTIVE").length.result)
}
