package models.domain.genomics

import play.api.libs.json.*

/**
 * Represents the naming status of a variant.
 *
 * Each status has associated properties:
 * - `dbValue`: The string stored in the database
 * - `displayName`: Human-readable name for UI display
 * - `isNamed`: Whether the variant has an official name
 */
enum NamingStatus(val dbValue: String, val displayName: String, val isNamed: Boolean) {
  /**
   * Variant has no official name - typically identified only by coordinates.
   */
  case Unnamed extends NamingStatus("UNNAMED", "Unnamed", false)

  /**
   * Variant has been submitted for naming review but not yet approved.
   */
  case PendingReview extends NamingStatus("PENDING_REVIEW", "Pending Review", false)

  /**
   * Variant has an official canonical name.
   */
  case Named extends NamingStatus("NAMED", "Named", true)

  override def toString: String = dbValue
}

object NamingStatus {
  /**
   * Parse a database string value to NamingStatus.
   */
  def fromString(str: String): Option[NamingStatus] = str.toUpperCase match {
    case "UNNAMED" => Some(Unnamed)
    case "PENDING_REVIEW" => Some(PendingReview)
    case "NAMED" => Some(Named)
    case _ => None
  }

  /**
   * Parse with a default fallback.
   */
  def fromStringOrDefault(str: String, default: NamingStatus = Unnamed): NamingStatus =
    fromString(str).getOrElse(default)

  // JSON serialization
  implicit val reads: Reads[NamingStatus] = Reads.StringReads.flatMap { str =>
    fromString(str) match {
      case Some(ns) => Reads.pure(ns)
      case None => Reads.failed(s"Invalid NamingStatus: $str")
    }
  }

  implicit val writes: Writes[NamingStatus] = Writes.StringWrites.contramap(_.dbValue)

  implicit val format: Format[NamingStatus] = Format(reads, writes)
}
