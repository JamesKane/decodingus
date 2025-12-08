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

/**
 * Represents the method by which genomic data was generated.
 */
enum DataGenerationMethod {
  case Sequencing, Genotyping

  override def toString: String = this match {
    case Sequencing => "SEQUENCING"
    case Genotyping => "GENOTYPING"
  }
}

object DataGenerationMethod {
  def fromString(str: String): Option[DataGenerationMethod] = str.toUpperCase match {
    case "SEQUENCING" => Some(Sequencing)
    case "GENOTYPING" => Some(Genotyping)
    case _ => None
  }
}

/**
 * Represents the target region of a genetic test.
 */
enum TargetType {
  case WholeGenome, YChromosome, MtDna, Autosomal, XChromosome, Mixed

  override def toString: String = this match {
    case WholeGenome => "WHOLE_GENOME"
    case YChromosome => "Y_CHROMOSOME"
    case MtDna => "MT_DNA"
    case Autosomal => "AUTOSOMAL"
    case XChromosome => "X_CHROMOSOME"
    case Mixed => "MIXED"
  }
}

object TargetType {
  def fromString(str: String): Option[TargetType] = str.toUpperCase match {
    case "WHOLE_GENOME" => Some(WholeGenome)
    case "Y_CHROMOSOME" => Some(YChromosome)
    case "MT_DNA" => Some(MtDna)
    case "AUTOSOMAL" => Some(Autosomal)
    case "X_CHROMOSOME" => Some(XChromosome)
    case "MIXED" => Some(Mixed)
    case _ => None
  }
}


// Case class to represent a row in the test_type_definition table
case class TestTypeRow(
  id: Option[Int] = None,
  code: String, // Changed from name: TestType to code: String, as table column is VARCHAR
  displayName: String,
  category: DataGenerationMethod, // Added
  vendor: Option[String] = None,
  targetType: TargetType, // Added
  expectedMinDepth: Option[Double] = None,
  expectedTargetDepth: Option[Double] = None,
  expectedMarkerCount: Option[Int] = None,
  supportsHaplogroupY: Boolean,
  supportsHaplogroupMt: Boolean,
  supportsAutosomalIbd: Boolean,
  supportsAncestry: Boolean,
  typicalFileFormats: List[String], // Changed from Seq[String] to List[String]
  version: Option[String] = None,
  releaseDate: Option[java.time.LocalDate] = None,
  deprecatedAt: Option[java.time.LocalDate] = None,
  successorTestTypeId: Option[Int] = None,
  description: Option[String] = None,
  documentationUrl: Option[String] = None
)