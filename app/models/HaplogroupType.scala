package models

import play.api.libs.json.{Format, Reads, Writes}
import play.api.mvc.QueryStringBindable

/**
 * Represents a type of haplogroup classification, distinguishing between paternal (Y) and maternal (MT) lineages.
 *
 * Enumeration values:
 * - `Y`: Represents the Y-DNA haplogroup type, associated with the paternal lineage.
 * - `MT`: Represents the mtDNA (mitochondrial DNA) haplogroup type, associated with the maternal lineage.
 *
 * This enumeration provides a structured way to classify genetic lineage data based on the type of haplogroup.
 */
enum HaplogroupType {
  case Y, MT

  override def toString: String = this match {
    case Y => "Y"
    case MT => "MT"
  }
}

/**
 * Provides methods for working with the HaplogroupType enumeration, which represents types of haplogroup classifications
 * (e.g., Y-DNA for paternal lineage and mtDNA for maternal lineage).
 *
 * This companion object includes utility methods for handling HaplogroupType values.
 */
object HaplogroupType {
  def fromString(str: String): Option[HaplogroupType] = str.toUpperCase match {
    case "Y" => Some(Y)
    case "MT" => Some(MT)
    case _ => None
  }

  // JSON serialization
  implicit val reads: Reads[HaplogroupType] = Reads.StringReads.map { str =>
    fromString(str).getOrElse(throw new IllegalArgumentException(s"Invalid HaplogroupType: $str"))
  }

  implicit val writes: Writes[HaplogroupType] = Writes.StringWrites.contramap(_.toString)

  implicit val format: Format[HaplogroupType] = Format(reads, writes)

  implicit val queryStringBindable: QueryStringBindable[HaplogroupType] =
    new QueryStringBindable[HaplogroupType] {
      def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, HaplogroupType]] = {
        params.get(key).flatMap(_.headOption).map { value =>
          try {
            Right(HaplogroupType.valueOf(value))
          } catch {
            case _: IllegalArgumentException =>
              Left(s"Invalid HaplogroupType value: $value")
          }
        }
      }

      def unbind(key: String, value: HaplogroupType): String = {
        s"$key=${value.toString}"
      }
    }

}