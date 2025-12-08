package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.{TestType, TestTypeRow} // Import the TestTypeRow case class
import slick.lifted.ProvenShape

/**
 * Represents the Slick table definition for storing different types of genetic tests or sequencing methodologies.
 * This table is used to define a taxonomy of test types that can be referenced throughout the system.
 *
 * @param tag A Slick Tag object used for binding the table to the database schema.
 */
class TestTypeTable(tag: Tag) extends Table[TestTypeRow](tag, Some("public"), "test_type_definition") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def name = column[TestType]("name", O.Unique) // Mapped directly to TestType enum
  def description = column[Option[String]]("description")

  def * : ProvenShape[TestTypeRow] = (id.?, name, description) <> ((TestTypeRow.apply _).tupled, TestTypeRow.unapply)
}
