package models.dal

import models.AncestryAnalysis
import models.dal.MyPostgresProfile.api.*

import java.util.UUID

/**
 * Represents the ancestry analysis table in the database.
 *
 * This table stores information about ancestry analyses performed on various samples, including the
 * associated analysis method, population information, and probability outcomes. Each record corresponds
 * to a single ancestry analysis with links to relevant samples and populations.
 *
 * @constructor Creates an instance of the AncestryAnalysisTable.
 * @param tag Represents the table's context in Slick's query mechanism.
 */
class AncestryAnalysisTable(tag: Tag) extends Table[AncestryAnalysis](tag, "ancestry_analysis") {
  def id = column[Int]("ancestry_analysis_id", O.PrimaryKey, O.AutoInc)

  def sampleGuid = column[UUID]("sample_guid")

  def analyticMethodId = column[Int]("analysis_method_id")

  def populationId = column[Int]("population_id")

  def probability = column[Double]("probability")

  def * = (id.?, sampleGuid, analyticMethodId, populationId, probability).mapTo[AncestryAnalysis]
}
