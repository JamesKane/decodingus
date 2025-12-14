package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*
import models.dal.domain.haplogroups.HaplogroupsTable
import play.api.libs.json.{JsValue, Json, OFormat}

import java.time.Instant

/**
 * Represents an ASR-reconstructed character state at a haplogroup node.
 *
 * This table stores the inferred ancestral state for each variant at each
 * haplogroup in the tree. Used for:
 * - SNPs: ancestral vs derived allele
 * - STRs: inferred repeat count (modal haplotype)
 * - SVs: presence/absence, orientation, copy number
 *
 * @param id                  Auto-generated primary key
 * @param haplogroupId        FK to the haplogroup node
 * @param variantId           FK to the variant
 * @param inferredState       The reconstructed state (allele, count, "present"/"absent", etc.)
 * @param confidence          Confidence score from ASR algorithm (0.0-1.0)
 * @param stateProbabilities  JSONB probability distribution for uncertain reconstructions
 * @param algorithm           ASR method used: "parsimony", "ml", "bayesian"
 * @param reconstructedAt     Timestamp of reconstruction
 */
case class HaplogroupCharacterState(
  id: Option[Int] = None,
  haplogroupId: Int,
  variantId: Int,
  inferredState: String,
  confidence: Option[BigDecimal] = None,
  stateProbabilities: Option[JsValue] = None,
  algorithm: Option[String] = None,
  reconstructedAt: Instant = Instant.now()
)

object HaplogroupCharacterState {
  implicit val format: OFormat[HaplogroupCharacterState] = Json.format[HaplogroupCharacterState]
}

/**
 * Slick table definition for haplogroup_character_state.
 */
class HaplogroupCharacterStateTable(tag: Tag)
  extends Table[HaplogroupCharacterState](tag, Some("public"), "haplogroup_character_state") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def haplogroupId = column[Int]("haplogroup_id")

  def variantId = column[Int]("variant_id")

  def inferredState = column[String]("inferred_state")

  def confidence = column[Option[BigDecimal]]("confidence")

  def stateProbabilities = column[Option[JsValue]]("state_probabilities")

  def algorithm = column[Option[String]]("algorithm")

  def reconstructedAt = column[Instant]("reconstructed_at")

  def * = (
    id.?,
    haplogroupId,
    variantId,
    inferredState,
    confidence,
    stateProbabilities,
    algorithm,
    reconstructedAt
  ).mapTo[HaplogroupCharacterState]

  def haplogroupFK = foreignKey(
    "haplogroup_character_state_haplogroup_fk",
    haplogroupId,
    TableQuery[HaplogroupsTable]
  )(_.haplogroupId, onDelete = ForeignKeyAction.Cascade)

  def variantFK = foreignKey(
    "haplogroup_character_state_variant_fk",
    variantId,
    TableQuery[VariantV2Table]
  )(_.variantId, onDelete = ForeignKeyAction.Cascade)

  def uniqueHaplogroupVariant = index(
    "idx_character_state_unique",
    (haplogroupId, variantId),
    unique = true
  )
}
