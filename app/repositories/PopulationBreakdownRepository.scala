package repositories

import models.domain.genomics.{PopulationBreakdown, PopulationComponent, SuperPopulationSummary}

import java.util.UUID
import scala.concurrent.Future

trait PopulationBreakdownRepository {
  def findById(id: Int): Future[Option[PopulationBreakdown]]
  def findByAtUri(atUri: String): Future[Option[PopulationBreakdown]]
  def findBySampleGuid(sampleGuid: UUID): Future[Option[PopulationBreakdown]]
  def create(breakdown: PopulationBreakdown): Future[PopulationBreakdown]
  def upsertByAtUri(breakdown: PopulationBreakdown): Future[PopulationBreakdown]
  def update(breakdown: PopulationBreakdown): Future[Boolean]
  def softDelete(id: Int): Future[Boolean]

  // Population Components
  def findComponentsByBreakdownId(breakdownId: Int): Future[Seq[PopulationComponent]]
  def createComponent(component: PopulationComponent): Future[PopulationComponent]
  def upsertComponentsByBreakdownId(breakdownId: Int, components: Seq[PopulationComponent]): Future[Seq[PopulationComponent]]

  // Super Population Summaries
  def findSummariesByBreakdownId(breakdownId: Int): Future[Seq[SuperPopulationSummary]]
  def createSummary(summary: SuperPopulationSummary): Future[SuperPopulationSummary]
  def upsertSummariesByBreakdownId(breakdownId: Int, summaries: Seq[SuperPopulationSummary]): Future[Seq[SuperPopulationSummary]]
}
