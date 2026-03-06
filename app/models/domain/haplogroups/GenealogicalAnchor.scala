package models.domain.haplogroups

import play.api.libs.json.{Json, OFormat}

import java.time.LocalDateTime

case class GenealogicalAnchor(
  id: Option[Int] = None,
  haplogroupId: Int,
  anchorType: AnchorType,
  dateCe: Int,
  dateUncertaintyYears: Option[Int],
  confidence: Option[BigDecimal],
  description: Option[String],
  source: Option[String],
  carbonDateBp: Option[Int],
  carbonDateSigma: Option[Int],
  createdBy: Option[String],
  createdAt: LocalDateTime = LocalDateTime.now()
) {

  /** Convert calendar year to YBP (years before 1950). */
  def toYbp: Int = 1950 - dateCe

  /** Get the age constraint as an AgeEstimate. */
  def toAgeEstimate: AgeEstimate = {
    val ybp = toYbp
    val lower = dateUncertaintyYears.map(u => ybp - u)
    val upper = dateUncertaintyYears.map(u => ybp + u)
    AgeEstimate(ybp, lower, upper)
  }
}

sealed trait AnchorType {
  def dbValue: String
}

object AnchorType {
  case object KnownMrca extends AnchorType { val dbValue = "KNOWN_MRCA" }
  case object Mdka extends AnchorType { val dbValue = "MDKA" }
  case object AncientDna extends AnchorType { val dbValue = "ANCIENT_DNA" }

  def fromString(s: String): AnchorType = s match {
    case "KNOWN_MRCA"  => KnownMrca
    case "MDKA"        => Mdka
    case "ANCIENT_DNA" => AncientDna
    case other         => throw new IllegalArgumentException(s"Unknown anchor type: $other")
  }

  val values: Seq[AnchorType] = Seq(KnownMrca, Mdka, AncientDna)
}
