package models.dal

import models.ReportedVariant
import slick.jdbc.PostgresProfile.api.*

import java.util.UUID

/**
 * Represents the database table definition for reported genetic variants associated with samples.
 *
 * The `ReportedVariantsTable` maps the `reported_variant` table in the database, defining its schema
 * and providing functionality for querying and interacting with its data. Each record corresponds to a reported
 * genetic variant annotated with detailed metadata regarding its location, attributes, and status.
 *
 * Table columns:
 * - `id`: A unique identifier for the reported variant (Primary Key, Auto Incremented).
 * - `sampleGuid`: The UUID of the sample that this variant is associated with.
 * - `genbankContigId`: The ID of the contig in the GenBank database where the variant resides.
 * - `position`: The specific genomic coordinate (position) of the variant on the contig.
 * - `referenceAllele`: The expected base or set of bases in the reference genome at the variant's position.
 * - `alternateAllele`: The observed alternate base or set of bases indicating the variation.
 * - `variantType`: The type of genetic variation (e.g., SNP, insertion, deletion).
 * - `reportedDate`: The datetime when the variant was reported or recorded.
 * - `provenance`: Information about the origin or source of the reported data.
 * - `confidenceScore`: A numerical value expressing the confidence in the accuracy of the reported data.
 * - `notes`: Free-form textual comments providing additional information about the reported variant.
 * - `status`: The reporting status of the variant (e.g., confirmed, pending, rejected).
 *
 * The table enables structured storage and retrieval of reported variants, facilitating advanced analyses
 * and integration with other genomic datasets.
 */
class ReportedVariantsTable(tag: Tag) extends Table[ReportedVariant](tag, "reported_variant") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

  def sampleGuid = column[UUID]("sample_guid")

  def genbankContigId = column[Int]("contig_id")

  def position = column[Int]("position")

  def referenceAllele = column[String]("reference_allele")

  def alternateAllele = column[String]("alternate_allele")

  def variantType = column[String]("variant_type")

  def reportedDate = column[java.time.LocalDateTime]("reported_date")

  def provenance = column[String]("provenance")

  def confidenceScore = column[Double]("confidence_score")

  def notes = column[String]("notes")

  def status = column[String]("status")

  def * = (id.?, sampleGuid, genbankContigId, position, referenceAllele, alternateAllele, variantType, reportedDate, provenance, confidenceScore, notes, status).mapTo[ReportedVariant]
}
