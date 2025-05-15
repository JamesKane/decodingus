package models.dal

import models.ReportedVariant
import slick.jdbc.PostgresProfile.api.*

import java.util.UUID

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
