package models.domain.haplogroups

import java.time.LocalDateTime

case class HaplogroupAncestralStr(
  id: Option[Int] = None,
  haplogroupId: Int,
  markerName: String,
  ancestralValue: Option[Int],
  ancestralValueAlt: Option[List[Int]],
  confidence: Option[BigDecimal],
  supportingSamples: Option[Int],
  variance: Option[BigDecimal],
  computedAt: LocalDateTime = LocalDateTime.now(),
  method: MotifMethod = MotifMethod.Modal
)

sealed trait MotifMethod {
  def dbValue: String
}

object MotifMethod {
  case object Modal extends MotifMethod { val dbValue = "MODAL" }
  case object Phylogenetic extends MotifMethod { val dbValue = "PHYLOGENETIC" }
  case object Manual extends MotifMethod { val dbValue = "MANUAL" }

  def fromString(s: String): MotifMethod = s match {
    case "MODAL"         => Modal
    case "PHYLOGENETIC"  => Phylogenetic
    case "MANUAL"        => Manual
    case other           => throw new IllegalArgumentException(s"Unknown motif method: $other")
  }
}
