package models.dal

import models.ReportedNegativeVariant
import slick.jdbc.PostgresProfile.api.*

import java.time.LocalDateTime
import java.util.UUID

class ReportedNegativeVariantsTable(tag: Tag) extends Table[ReportedNegativeVariant](tag, "reported_negative_variant") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

  def sampleGuid = column[UUID]("sample_guid")

  def variantId = column[Int]("variant_id")

  def reportedDate = column[LocalDateTime]("reported_date")

  def notes = column[String]("notes")

  def status = column[String]("status")
  
  def * = (id.?, sampleGuid, variantId, reportedDate, notes, status).mapTo[ReportedNegativeVariant]
}
