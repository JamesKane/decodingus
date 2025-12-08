package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.{DataGenerationMethod, TargetType, TestType, TestTypeRow} // Import new enums and TestTypeRow
import slick.lifted.ProvenShape
import java.time.LocalDate // For releaseDate, deprecatedAt

/**
 * Represents the Slick table definition for storing different types of genetic tests or sequencing methodologies.
 * This table is used to define a taxonomy of test types that can be referenced throughout the system.
 *
 * @param tag A Slick Tag object used for binding the table to the database schema.
 */
class TestTypeTable(tag: Tag) extends Table[TestTypeRow](tag, Some("public"), "test_type_definition") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def code = column[String]("code", O.Unique) // Changed from name: TestType to code: String
  def displayName = column[String]("display_name")
  def category = column[DataGenerationMethod]("category") // Added
  def vendor = column[Option[String]]("vendor") // Added
  def targetType = column[TargetType]("target_type") // Added
  def expectedMinDepth = column[Option[Double]]("expected_min_depth")
  def expectedTargetDepth = column[Option[Double]]("expected_target_depth")
  def expectedMarkerCount = column[Option[Int]]("expected_marker_count")
  def supportsHaplogroupY = column[Boolean]("supports_haplogroup_y")
  def supportsHaplogroupMt = column[Boolean]("supportsHaplogroupMt")
  def supportsAutosomalIbd = column[Boolean]("supports_autosomal_ibd")
  def supportsAncestry = column[Boolean]("supports_ancestry")
  def typicalFileFormats = column[List[String]]("typical_file_formats") // Use List[String] for TEXT[], Slick-Pg handles List
  def version = column[Option[String]]("version")
  def releaseDate = column[Option[LocalDate]]("release_date")
  def deprecatedAt = column[Option[LocalDate]]("deprecated_at")
  def successorTestTypeId = column[Option[Int]]("successor_test_type_id")
  def description = column[Option[String]]("description")
  def documentationUrl = column[Option[String]]("documentation_url")

  // Projection for the case class
  def * : ProvenShape[TestTypeRow] = (
    id.?, code, displayName, category, vendor, targetType,
    expectedMinDepth, expectedTargetDepth, expectedMarkerCount,
    supportsHaplogroupY, supportsHaplogroupMt, supportsAutosomalIbd, supportsAncestry,
    typicalFileFormats, version, releaseDate, deprecatedAt, successorTestTypeId,
    description, documentationUrl
  ) <> ((TestTypeRow.apply _).tupled, TestTypeRow.unapply)
}