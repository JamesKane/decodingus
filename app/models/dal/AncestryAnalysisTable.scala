package models.dal

import models.AncestryAnalysis
import models.dal.MyPostgresProfile.api.*

import java.util.UUID

class AncestryAnalysisTable(tag: Tag) extends Table[AncestryAnalysis](tag, "ancestry_analysis") {
  def id = column[Int]("ancestry_analysis_id", O.PrimaryKey, O.AutoInc)
  def sampleGuid = column[UUID]("sample_guid")
  def analyticMethodId = column[Int]("analysis_method_id")
  def populationId = column[Int]("population_id")
  def probability = column[Double]("probability")
  
  def * = (id.?, sampleGuid, analyticMethodId, populationId, probability).mapTo[AncestryAnalysis]
}
