package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.PopulationComponent

/**
 * Slick table definition for population_component table.
 * Stores individual population components in an ancestry breakdown (~33 reference populations).
 */
class PopulationComponentTable(tag: Tag) extends Table[PopulationComponent](tag, "population_component") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def populationBreakdownId = column[Int]("population_breakdown_id")
  def populationCode = column[String]("population_code")
  def populationName = column[Option[String]]("population_name")
  def superPopulation = column[Option[String]]("super_population")
  def percentage = column[Double]("percentage")
  def confidenceLower = column[Option[Double]]("confidence_lower")
  def confidenceUpper = column[Option[Double]]("confidence_upper")
  def rank = column[Option[Int]]("rank")

  def * = (
    id.?,
    populationBreakdownId,
    populationCode,
    populationName,
    superPopulation,
    percentage,
    confidenceLower,
    confidenceUpper,
    rank
  ).mapTo[PopulationComponent]

  // Foreign key to population_breakdown
  def populationBreakdownFk = foreignKey(
    "fk_population_component_breakdown",
    populationBreakdownId,
    TableQuery[PopulationBreakdownTable]
  )(_.id, onDelete = ForeignKeyAction.Cascade)

  def breakdownIdx = index("idx_population_component_breakdown", populationBreakdownId)
}
