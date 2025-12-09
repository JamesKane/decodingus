package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.{PopulationBreakdown, PopulationComponent, SuperPopulationSummary}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PopulationBreakdownRepositoryImpl @Inject()(
  protected val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends PopulationBreakdownRepository with HasDatabaseConfigProvider[JdbcProfile] {

  private val breakdowns = DatabaseSchema.domain.genomics.populationBreakdowns
  private val components = DatabaseSchema.domain.genomics.populationComponents
  private val summaries = DatabaseSchema.domain.genomics.superPopulationSummaries

  override def findById(id: Int): Future[Option[PopulationBreakdown]] = {
    db.run(breakdowns.filter(b => b.id === id && !b.deleted).result.headOption)
  }

  override def findByAtUri(atUri: String): Future[Option[PopulationBreakdown]] = {
    db.run(breakdowns.filter(b => b.atUri === atUri && !b.deleted).result.headOption)
  }

  override def findBySampleGuid(sampleGuid: UUID): Future[Option[PopulationBreakdown]] = {
    db.run(breakdowns.filter(b => b.sampleGuid === sampleGuid && !b.deleted).result.headOption)
  }

  override def create(breakdown: PopulationBreakdown): Future[PopulationBreakdown] = {
    db.run(
      (breakdowns returning breakdowns.map(_.id)
        into ((b, id) => b.copy(id = Some(id)))) += breakdown
    )
  }

  override def upsertByAtUri(breakdown: PopulationBreakdown): Future[PopulationBreakdown] = {
    breakdown.atUri match {
      case None => create(breakdown)
      case Some(uri) =>
        findByAtUri(uri).flatMap {
          case Some(existing) =>
            val updated = breakdown.copy(
              id = existing.id,
              createdAt = existing.createdAt,
              updatedAt = LocalDateTime.now()
            )
            update(updated).map(_ => updated)
          case None => create(breakdown)
        }
    }
  }

  override def update(breakdown: PopulationBreakdown): Future[Boolean] = {
    breakdown.id match {
      case None => Future.successful(false)
      case Some(id) =>
        val updated = breakdown.copy(updatedAt = LocalDateTime.now())
        db.run(breakdowns.filter(_.id === id).update(updated)).map(_ > 0)
    }
  }

  override def softDelete(id: Int): Future[Boolean] = {
    db.run(
      breakdowns.filter(_.id === id)
        .map(b => (b.deleted, b.updatedAt))
        .update((true, LocalDateTime.now()))
    ).map(_ > 0)
  }

  // Population Components
  override def findComponentsByBreakdownId(breakdownId: Int): Future[Seq[PopulationComponent]] = {
    db.run(components.filter(_.populationBreakdownId === breakdownId).result)
  }

  override def createComponent(component: PopulationComponent): Future[PopulationComponent] = {
    db.run(
      (components returning components.map(_.id)
        into ((c, id) => c.copy(id = Some(id)))) += component
    )
  }

  override def upsertComponentsByBreakdownId(breakdownId: Int, newComponents: Seq[PopulationComponent]): Future[Seq[PopulationComponent]] = {
    val action = for {
      _ <- components.filter(_.populationBreakdownId === breakdownId).delete
      result <- (components returning components.map(_.id)
        into ((c, id) => c.copy(id = Some(id)))) ++= newComponents.map(_.copy(populationBreakdownId = breakdownId))
    } yield result
    db.run(action.transactionally)
  }

  // Super Population Summaries
  override def findSummariesByBreakdownId(breakdownId: Int): Future[Seq[SuperPopulationSummary]] = {
    db.run(summaries.filter(_.populationBreakdownId === breakdownId).result)
  }

  override def createSummary(summary: SuperPopulationSummary): Future[SuperPopulationSummary] = {
    db.run(
      (summaries returning summaries.map(_.id)
        into ((s, id) => s.copy(id = Some(id)))) += summary
    )
  }

  override def upsertSummariesByBreakdownId(breakdownId: Int, newSummaries: Seq[SuperPopulationSummary]): Future[Seq[SuperPopulationSummary]] = {
    val action = for {
      _ <- summaries.filter(_.populationBreakdownId === breakdownId).delete
      result <- (summaries returning summaries.map(_.id)
        into ((s, id) => s.copy(id = Some(id)))) ++= newSummaries.map(_.copy(populationBreakdownId = breakdownId))
    } yield result
    db.run(action.transactionally)
  }
}
