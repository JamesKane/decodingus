package models.dal.domain.haplogroups

import models.dal.MyPostgresProfile.api.*
import models.domain.haplogroups.{AnchorType, GenealogicalAnchor}

import java.time.LocalDateTime

class GenealogicalAnchorTable(tag: Tag) extends Table[GenealogicalAnchor](tag, Some("tree"), "genealogical_anchor") {

  implicit val anchorTypeMapper: BaseColumnType[AnchorType] =
    MappedColumnType.base[AnchorType, String](_.dbValue, AnchorType.fromString)

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def haplogroupId = column[Int]("haplogroup_id")
  def anchorType = column[AnchorType]("anchor_type")
  def dateCe = column[Int]("date_ce")
  def dateUncertaintyYears = column[Option[Int]]("date_uncertainty_years")
  def confidence = column[Option[BigDecimal]]("confidence")
  def description = column[Option[String]]("description")
  def source = column[Option[String]]("source")
  def carbonDateBp = column[Option[Int]]("carbon_date_bp")
  def carbonDateSigma = column[Option[Int]]("carbon_date_sigma")
  def createdBy = column[Option[String]]("created_by")
  def createdAt = column[LocalDateTime]("created_at")

  def * = (
    id.?, haplogroupId, anchorType, dateCe, dateUncertaintyYears,
    confidence, description, source, carbonDateBp, carbonDateSigma,
    createdBy, createdAt
  ).mapTo[GenealogicalAnchor]

  def haplogroupFK = foreignKey(
    "genealogical_anchor_haplogroup_fk",
    haplogroupId,
    TableQuery[HaplogroupsTable]
  )(_.haplogroupId, onDelete = ForeignKeyAction.Cascade)
}
