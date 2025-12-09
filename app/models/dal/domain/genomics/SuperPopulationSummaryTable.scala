package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.{SuperPopulationListJsonb, SuperPopulationSummary}

/**
 * Slick table definition for super_population_summary table.
 * Stores aggregated ancestry at continental level (9 super-populations).
 */
class SuperPopulationSummaryTable(tag: Tag) extends Table[SuperPopulationSummary](tag, "super_population_summary") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def populationBreakdownId = column[Int]("population_breakdown_id")
  def superPopulation = column[String]("super_population")
  def percentage = column[Double]("percentage")
  def populations = column[Option[SuperPopulationListJsonb]]("populations")

  def * = (
    id.?,
    populationBreakdownId,
    superPopulation,
    percentage,
    populations
  ).mapTo[SuperPopulationSummary]

  // Foreign key to population_breakdown
  def populationBreakdownFk = foreignKey(
    "fk_super_population_breakdown",
    populationBreakdownId,
    TableQuery[PopulationBreakdownTable]
  )(_.id, onDelete = ForeignKeyAction.Cascade)

  def breakdownIdx = index("idx_super_population_breakdown", populationBreakdownId)
}
