package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*
import play.api.libs.json.{Json, OFormat}

import java.time.Instant

/**
 * Per-marker STR mutation rates for ASR and age estimation.
 *
 * Sources include Ballantyne 2010, Willems 2016, and other published studies.
 * These rates are critical for accurate branch age estimation using the
 * stepwise mutation model.
 *
 * @param id                Auto-generated primary key
 * @param markerName        STR marker name (e.g., DYS456, DYS389I)
 * @param panelNames        Panels containing this marker (PowerPlex, YHRD, BigY, etc.)
 * @param mutationRate      Mutations per generation
 * @param mutationRateLower 95% CI lower bound
 * @param mutationRateUpper 95% CI upper bound
 * @param omegaPlus         Probability of expansion (default 0.5)
 * @param omegaMinus        Probability of contraction (default 0.5)
 * @param multiStepRate     Combined rate for multi-step mutations (omega_2 + omega_3 + ...)
 * @param source            Publication source (e.g., "Ballantyne 2010")
 * @param createdAt         When the rate was recorded
 */
case class StrMutationRate(
  id: Option[Int] = None,
  markerName: String,
  panelNames: Option[List[String]] = None,
  mutationRate: BigDecimal,
  mutationRateLower: Option[BigDecimal] = None,
  mutationRateUpper: Option[BigDecimal] = None,
  omegaPlus: Option[BigDecimal] = Some(BigDecimal("0.5")),
  omegaMinus: Option[BigDecimal] = Some(BigDecimal("0.5")),
  multiStepRate: Option[BigDecimal] = None,
  source: Option[String] = None,
  createdAt: Instant = Instant.now()
) {

  /**
   * Check if mutation is biased toward expansion.
   */
  def isExpansionBiased: Boolean =
    omegaPlus.getOrElse(BigDecimal("0.5")) > BigDecimal("0.5")

  /**
   * Check if mutation is biased toward contraction.
   */
  def isContractionBiased: Boolean =
    omegaMinus.getOrElse(BigDecimal("0.5")) > BigDecimal("0.5")

  /**
   * Get the symmetry of mutation direction (1.0 = perfectly symmetric).
   * Values < 1.0 indicate directional bias.
   */
  def directionalSymmetry: BigDecimal = {
    val plus = omegaPlus.getOrElse(BigDecimal("0.5"))
    val minus = omegaMinus.getOrElse(BigDecimal("0.5"))
    if (plus >= minus) minus / plus else plus / minus
  }
}

object StrMutationRate {
  implicit val format: OFormat[StrMutationRate] = Json.format[StrMutationRate]

  /**
   * Create a rate entry with symmetric mutation probability.
   */
  def symmetric(
    markerName: String,
    rate: BigDecimal,
    source: String
  ): StrMutationRate = StrMutationRate(
    markerName = markerName,
    mutationRate = rate,
    source = Some(source)
  )

  /**
   * Create a rate entry with directional bias.
   */
  def withBias(
    markerName: String,
    rate: BigDecimal,
    omegaPlus: BigDecimal,
    omegaMinus: BigDecimal,
    source: String
  ): StrMutationRate = StrMutationRate(
    markerName = markerName,
    mutationRate = rate,
    omegaPlus = Some(omegaPlus),
    omegaMinus = Some(omegaMinus),
    source = Some(source)
  )
}

/**
 * Slick table definition for str_mutation_rate.
 */
class StrMutationRateTable(tag: Tag)
  extends Table[StrMutationRate](tag, Some("public"), "str_mutation_rate") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def markerName = column[String]("marker_name")

  def panelNames = column[Option[List[String]]]("panel_names")

  def mutationRate = column[BigDecimal]("mutation_rate")

  def mutationRateLower = column[Option[BigDecimal]]("mutation_rate_lower")

  def mutationRateUpper = column[Option[BigDecimal]]("mutation_rate_upper")

  def omegaPlus = column[Option[BigDecimal]]("omega_plus")

  def omegaMinus = column[Option[BigDecimal]]("omega_minus")

  def multiStepRate = column[Option[BigDecimal]]("multi_step_rate")

  def source = column[Option[String]]("source")

  def createdAt = column[Instant]("created_at")

  def * = (
    id.?,
    markerName,
    panelNames,
    mutationRate,
    mutationRateLower,
    mutationRateUpper,
    omegaPlus,
    omegaMinus,
    multiStepRate,
    source,
    createdAt
  ).mapTo[StrMutationRate]

  def uniqueMarkerName = index(
    "idx_str_mutation_rate_marker_unique",
    markerName,
    unique = true
  )
}
