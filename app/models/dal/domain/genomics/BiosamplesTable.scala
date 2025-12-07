package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.Biosample

import java.util.UUID

/**
 * Defines the Slick table mapping for the `Biosample` entity. This table represents 
 * biological samples with their core identifiers and metadata.
 *
 * @param tag The Slick table tag used for binding the table to the database schema.
 */
class BiosamplesTable(tag: Tag) extends Table[Biosample](tag, "biosample") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def sampleAccession = column[String]("sample_accession", O.Unique)

  def description = column[String]("description")

  def alias = column[Option[String]]("alias")

  def centerName = column[String]("center_name")

  def specimenDonorId = column[Option[Int]]("specimen_donor_id")

  def sampleGuid = column[UUID]("sample_guid")

  def locked = column[Boolean]("locked", O.Default(false))

  def sourcePlatform = column[Option[String]]("source_platform")

  def * = (
    id.?,
    sampleGuid,
    sampleAccession,
    description,
    alias,
    centerName,
    specimenDonorId,
    locked,
    sourcePlatform
  ).mapTo[Biosample]
}