package models.domain.genomics

import play.api.libs.json.*

/**
 * Represents the type of genetic mutation.
 *
 * Each mutation type has associated properties:
 * - `dbValue`: The string stored in the database
 * - `category`: Classification as Point, Repeat, or Structural
 * - `displayName`: Human-readable name for UI display
 */
enum MutationType(val dbValue: String, val category: MutationCategory, val displayName: String) {
  // Point mutations - single nucleotide or small changes
  case SNP extends MutationType("SNP", MutationCategory.Point, "Single Nucleotide Polymorphism")
  case INDEL extends MutationType("INDEL", MutationCategory.Point, "Insertion/Deletion")
  case MNP extends MutationType("MNP", MutationCategory.Point, "Multi-Nucleotide Polymorphism")

  // Repeat variations
  case STR extends MutationType("STR", MutationCategory.Repeat, "Short Tandem Repeat")

  // Structural variants - larger genomic rearrangements
  case DEL extends MutationType("DEL", MutationCategory.Structural, "Deletion")
  case DUP extends MutationType("DUP", MutationCategory.Structural, "Duplication")
  case INS extends MutationType("INS", MutationCategory.Structural, "Insertion")
  case INV extends MutationType("INV", MutationCategory.Structural, "Inversion")
  case CNV extends MutationType("CNV", MutationCategory.Structural, "Copy Number Variant")
  case TRANS extends MutationType("TRANS", MutationCategory.Structural, "Translocation")

  override def toString: String = dbValue

  def isPointMutation: Boolean = category == MutationCategory.Point
  def isRepeat: Boolean = category == MutationCategory.Repeat
  def isStructural: Boolean = category == MutationCategory.Structural
}

/**
 * Category of mutation types.
 */
enum MutationCategory {
  case Point, Repeat, Structural
}

object MutationType {
  /**
   * Parse a database string value to MutationType.
   */
  def fromString(str: String): Option[MutationType] = str.toUpperCase match {
    case "SNP" => Some(SNP)
    case "INDEL" => Some(INDEL)
    case "MNP" => Some(MNP)
    case "STR" => Some(STR)
    case "DEL" => Some(DEL)
    case "DUP" => Some(DUP)
    case "INS" => Some(INS)
    case "INV" => Some(INV)
    case "CNV" => Some(CNV)
    case "TRANS" => Some(TRANS)
    case _ => None
  }

  /**
   * Parse with a default fallback.
   */
  def fromStringOrDefault(str: String, default: MutationType = SNP): MutationType =
    fromString(str).getOrElse(default)

  /**
   * All point mutation types.
   */
  val pointTypes: Set[MutationType] = Set(SNP, INDEL, MNP)

  /**
   * All structural variant types.
   */
  val structuralTypes: Set[MutationType] = Set(DEL, DUP, INS, INV, CNV, TRANS)

  /**
   * All mutation types.
   */
  val allTypes: Set[MutationType] = MutationType.values.toSet

  // JSON serialization
  implicit val reads: Reads[MutationType] = Reads.StringReads.flatMap { str =>
    fromString(str) match {
      case Some(mt) => Reads.pure(mt)
      case None => Reads.failed(s"Invalid MutationType: $str")
    }
  }

  implicit val writes: Writes[MutationType] = Writes.StringWrites.contramap(_.dbValue)

  implicit val format: Format[MutationType] = Format(reads, writes)
}
