package models.api

import play.api.libs.json.{Json, OFormat}

import java.time.ZonedDateTime

case class TreeDTO(name: String, crumbs: List[CrumbDTO], subclade: Option[TreeNodeDTO])

case class CrumbDTO(label: String, url: String)

case class TreeNodeDTO(name: String, variants: Seq[VariantDTO], children: List[TreeNodeDTO], updated: ZonedDateTime, isBackbone: Boolean = false) {
  def weight: Int = 1 + children.map(_.weight).sum
}

case class GenomicCoordinate(start: Int, stop: Int, anc: String, der: String) {
  @Override
  override def toString: String = s"$start $anc->$der"
}

case class VariantDTO(
                       name: String,
                       coordinates: Map[String, GenomicCoordinate],
                       variantType: String
                     )

object GenomicCoordinate {
  implicit val featureCoordFormats: OFormat[GenomicCoordinate] = Json.format[GenomicCoordinate]
}

object VariantDTO {
  implicit val treeFeatureFormats: OFormat[VariantDTO] = Json.format[VariantDTO]
}

object TreeNodeDTO {
  implicit val treeNodeFormats: OFormat[TreeNodeDTO] = Json.format[TreeNodeDTO]
}

object CrumbDTO {
  implicit val crumbFormats: OFormat[CrumbDTO] = Json.format[CrumbDTO]
}

object TreeDTO {
  implicit val treeFormats: OFormat[TreeDTO] = Json.format[TreeDTO]
}