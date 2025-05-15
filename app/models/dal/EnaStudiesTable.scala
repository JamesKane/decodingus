package models.dal

import models.EnaStudy
import models.dal.MyPostgresProfile.api.*

class EnaStudiesTable(tag: Tag) extends Table[EnaStudy](tag, "ena_study") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def accession = column[String]("accession", O.Unique)
  def title = column[String]("title")
  def centerName = column[String]("center_name")
  def studyName = column[String]("study_name")
  def details = column[String]("details")
  
  def * = (id.?, accession, title, centerName, studyName, details).mapTo[EnaStudy]
}
