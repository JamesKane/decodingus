package models.domain.genomics

import play.api.mvc.QueryStringBindable

/**
 * Represents different types of genetic tests or sequencing methodologies.
 * This enum provides a structured way to classify genomic data based on how it was generated.
 */
enum TestType {
  case WGS, WES, TARGETED_Y, TARGETED_MT, SNP_ARRAY_23ANDME, SNP_ARRAY_ANCESTRY

  override def toString: String = this match {
    case WGS => "WGS"
    case WES => "WES"
    case TARGETED_Y => "TARGETED_Y"
    case TARGETED_MT => "TARGETED_MT"
    case SNP_ARRAY_23ANDME => "SNP_ARRAY_23ANDME"
    case SNP_ARRAY_ANCESTRY => "SNP_ARRAY_ANCESTRY"
  }
}

/**
 * Companion object for the TestType enumeration, providing utility methods and implicits.
 */
object TestType {
  /**
   * Converts a string representation to a TestType enum value.
   *
   * @param str The string to convert.
   * @return An Option containing the corresponding TestType, or None if the string does not match.
   */
  def fromString(str: String): Option[TestType] = str.toUpperCase match {
    case "WGS" => Some(WGS)
    case "WES" => Some(WES)
    case "TARGETED_Y" => Some(TARGETED_Y)
    case "TARGETED_MT" => Some(TARGETED_MT)
    case "SNP_ARRAY_23ANDME" => Some(SNP_ARRAY_23ANDME)
    case "SNP_ARRAY_ANCESTRY" => Some(SNP_ARRAY_ANCESTRY)
    case _ => None
  }

  /**
   * Implicit QueryStringBindable for TestType, allowing it to be used directly in Play routes.
   */
  implicit val queryStringBindable: QueryStringBindable[TestType] =
    new QueryStringBindable[TestType] {
      def bind(key: String, params: Map[String, Seq[String]]): Option[Either[String, TestType]] = {
        params.get(key).flatMap(_.headOption).map { value =>
          try {
            fromString(value) match {
              case Some(tt) => Right(tt)
              case None => Left(s"Invalid TestType value: $value")
            }
          } catch {
            case _: IllegalArgumentException =>
              Left(s"Invalid TestType value: $value")
          }
        }
      }

      def unbind(key: String, value: TestType): String = {
        s"$key=${value.toString}"
      }
    }
}

// Case class to represent a row in the test_type_definition table
case class TestTypeRow(
  id: Option[Int] = None,
  name: TestType,
  description: Option[String] = None
)