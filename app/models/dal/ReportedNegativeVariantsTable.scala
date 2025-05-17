package models.dal

import models.ReportedNegativeVariant
import slick.jdbc.PostgresProfile.api.*

import java.time.LocalDateTime
import java.util.UUID

/**
 * Represents the database table definition for storing reported negative variant records.
 *
 * This table is used to persist details about variants that have been reported as negative
 * (i.e., not associated with a particular observation or condition). Each record is tied
 * to a specific sample and variant, with optional notes and a status field to indicate
 * any additional metadata about the report.
 *
 * @constructor Initializes a new instance of the `ReportedNegativeVariantsTable` class,
 *              mapping columns to the attributes of a `ReportedNegativeVariant` entity.
 * @param tag A Slick `Tag` object used to scope and reference the table within the database schema.
 *
 *            Columns:
 *  - `id`: Unique identifier for the reported negative variant (primary key, auto-increment).
 *  - `sampleGuid`: The UUID uniquely identifying the sample associated with the reported variant.
 *  - `variantId`: Identifier for the specific variant being reported as negative.
 *  - `reportedDate`: The timestamp indicating when this variant was reported as negative.
 *  - `notes`: Optional attribute to include additional notes or comments regarding the report.
 *  - `status`: A string describing the status of the report (e.g., reviewed, pending, etc.).
 *
 * Primary key:
 *  - `id`: The unique identifier for the table, auto-generated.
 *
 * Mapping:
 *  - Defines a mapping to the `ReportedNegativeVariant` case class.
 */
class ReportedNegativeVariantsTable(tag: Tag) extends Table[ReportedNegativeVariant](tag, "reported_negative_variant") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

  def sampleGuid = column[UUID]("sample_guid")

  def variantId = column[Int]("variant_id")

  def reportedDate = column[LocalDateTime]("reported_date")

  def notes = column[String]("notes")

  def status = column[String]("status")

  def * = (id.?, sampleGuid, variantId, reportedDate, notes, status).mapTo[ReportedNegativeVariant]
}
