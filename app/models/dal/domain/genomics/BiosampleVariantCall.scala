package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*
import play.api.libs.json.{Json, OFormat}

import java.time.Instant

/**
 * Represents an observed variant call from a biosample.
 *
 * This is the input data for ASR - the actual observed states
 * from sequenced samples.
 *
 * @param id            Auto-generated primary key
 * @param biosampleId   FK to the biosample
 * @param variantId     FK to the variant
 * @param observedState The observed state (allele, repeat count, "present"/"absent")
 * @param qualityScore  Phred-scale quality score
 * @param readDepth     Number of reads supporting the call
 * @param confidence    Confidence level: "high", "medium", "low"
 * @param source        Data source: "ftdna", "yfull", "user_upload", etc.
 * @param createdAt     When the call was recorded
 */
case class BiosampleVariantCall(
  id: Option[Int] = None,
  biosampleId: Int,
  variantId: Int,
  observedState: String,
  qualityScore: Option[Int] = None,
  readDepth: Option[Int] = None,
  confidence: Option[String] = None,
  source: Option[String] = None,
  createdAt: Instant = Instant.now()
)

object BiosampleVariantCall {
  implicit val format: OFormat[BiosampleVariantCall] = Json.format[BiosampleVariantCall]

  object Confidence {
    val HIGH = "high"
    val MEDIUM = "medium"
    val LOW = "low"
  }
}

/**
 * Slick table definition for biosample_variant_call.
 */
class BiosampleVariantCallTable(tag: Tag)
  extends Table[BiosampleVariantCall](tag, Some("public"), "biosample_variant_call") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def biosampleId = column[Int]("biosample_id")

  def variantId = column[Int]("variant_id")

  def observedState = column[String]("observed_state")

  def qualityScore = column[Option[Int]]("quality_score")

  def readDepth = column[Option[Int]]("read_depth")

  def confidence = column[Option[String]]("confidence")

  def source = column[Option[String]]("source")

  def createdAt = column[Instant]("created_at")

  def * = (
    id.?,
    biosampleId,
    variantId,
    observedState,
    qualityScore,
    readDepth,
    confidence,
    source,
    createdAt
  ).mapTo[BiosampleVariantCall]

  // Note: biosample FK references public.biosample table
  // We don't define the FK here to avoid circular dependencies
  // The DB-level FK constraint handles referential integrity

  def variantFK = foreignKey(
    "biosample_variant_call_variant_fk",
    variantId,
    TableQuery[VariantV2Table]
  )(_.variantId, onDelete = ForeignKeyAction.Cascade)

  def uniqueBiosampleVariant = index(
    "idx_biosample_variant_call_unique",
    (biosampleId, variantId),
    unique = true
  )
}
