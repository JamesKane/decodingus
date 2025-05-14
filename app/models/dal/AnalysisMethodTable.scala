package models.dal

import models.AnalysisMethod
import models.dal.MyPostgresProfile.api.*

class AnalysisMethodTable(tag: Tag) extends Table[AnalysisMethod](tag, "analysis_method") {
  def id = column[Int]("analysis_method_id", O.PrimaryKey, O.AutoInc)
  def methodName = column[String]("method_name", O.Unique)
  
  def * = (id.?, methodName).mapTo[AnalysisMethod]
}
