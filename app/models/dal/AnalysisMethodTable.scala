package models.dal

import models.AnalysisMethod
import models.dal.MyPostgresProfile.api.*

/**
 * Represents the mapping for the "analysis_method" table in the database.
 *
 * This table is used to store various analysis methods that can be utilized
 * in genetic or ancestry-related analyses.
 *
 * @constructor Creates a new `AnalysisMethodTable` instance.
 * @param tag A Slick `Tag` object used to associate the table schema.
 *
 *            Columns:
 *            - `id`: The primary key of the table, representing the unique identifier for an analysis method.
 *            - `methodName`: A string column that stores the name of the analysis method. This column must be unique.
 *
 *            Mapping:
 *            The table schema maps to the `AnalysisMethod` case class.
 */
class AnalysisMethodTable(tag: Tag) extends Table[AnalysisMethod](tag, "analysis_method") {
  def id = column[Int]("analysis_method_id", O.PrimaryKey, O.AutoInc)

  def methodName = column[String]("method_name", O.Unique)

  def * = (id.?, methodName).mapTo[AnalysisMethod]
}
