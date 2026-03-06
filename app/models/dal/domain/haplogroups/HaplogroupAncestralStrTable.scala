package models.dal.domain.haplogroups

import models.dal.MyPostgresProfile.api.*
import models.domain.haplogroups.{HaplogroupAncestralStr, MotifMethod}

import java.time.LocalDateTime

class HaplogroupAncestralStrTable(tag: Tag) extends Table[HaplogroupAncestralStr](tag, Some("tree"), "haplogroup_ancestral_str") {

  implicit val motifMethodMapper: BaseColumnType[MotifMethod] =
    MappedColumnType.base[MotifMethod, String](_.dbValue, MotifMethod.fromString)

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def haplogroupId = column[Int]("haplogroup_id")
  def markerName = column[String]("marker_name")
  def ancestralValue = column[Option[Int]]("ancestral_value")
  def ancestralValueAlt = column[Option[List[Int]]]("ancestral_value_alt")
  def confidence = column[Option[BigDecimal]]("confidence")
  def supportingSamples = column[Option[Int]]("supporting_samples")
  def variance = column[Option[BigDecimal]]("variance")
  def computedAt = column[LocalDateTime]("computed_at")
  def method = column[MotifMethod]("method")

  def * = (
    id.?, haplogroupId, markerName, ancestralValue, ancestralValueAlt,
    confidence, supportingSamples, variance, computedAt, method
  ).mapTo[HaplogroupAncestralStr]

  def haplogroupFK = foreignKey(
    "hg_ancestral_str_haplogroup_fk",
    haplogroupId,
    TableQuery[HaplogroupsTable]
  )(_.haplogroupId, onDelete = ForeignKeyAction.Cascade)

  def uniqueHaplogroupMarker = index(
    "idx_hg_ancestral_str_unique",
    (haplogroupId, markerName),
    unique = true
  )
}
