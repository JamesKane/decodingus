package models.domain.genomics

import play.api.libs.json.*

import java.time.LocalDateTime
import java.util.UUID

/**
 * Represents an ancestry analysis breakdown using PCA projection onto reference populations.
 *
 * @param id                   Auto-generated primary key
 * @param atUri                AT URI for this record (for citizen-owned data)
 * @param atCid                Content identifier for version tracking
 * @param sampleGuid           UUID of the associated sample
 * @param analysisMethod       Analysis method used (e.g., PCA_PROJECTION_GMM, ADMIXTURE)
 * @param panelType            Panel type: "aims" (~5k SNPs) or "genome-wide" (~500k SNPs)
 * @param referencePopulations Reference panel name (e.g., "1000G_HGDP_v1")
 * @param snpsAnalyzed         Total SNPs in the analysis panel
 * @param snpsWithGenotype     SNPs with valid genotype calls
 * @param snpsMissing          SNPs with no call or missing data
 * @param confidenceLevel      Overall confidence 0.0-1.0
 * @param pcaCoordinates       First 3 PCA coordinates [x, y, z]
 * @param analysisDate         When the analysis was performed
 * @param pipelineVersion      Version of the analysis pipeline
 * @param referenceVersion     Version of the reference panel
 * @param deleted              Soft delete flag
 * @param createdAt            Record creation timestamp
 * @param updatedAt            Record update timestamp
 */
case class PopulationBreakdown(
                                id: Option[Int] = None,
                                atUri: Option[String] = None,
                                atCid: Option[String] = None,
                                sampleGuid: UUID,
                                analysisMethod: String,
                                panelType: Option[String] = None,
                                referencePopulations: Option[String] = None,
                                snpsAnalyzed: Option[Int] = None,
                                snpsWithGenotype: Option[Int] = None,
                                snpsMissing: Option[Int] = None,
                                confidenceLevel: Option[Double] = None,
                                pcaCoordinates: Option[PcaCoordinatesJsonb] = None,
                                analysisDate: Option[LocalDateTime] = None,
                                pipelineVersion: Option[String] = None,
                                referenceVersion: Option[String] = None,
                                deleted: Boolean = false,
                                createdAt: LocalDateTime = LocalDateTime.now(),
                                updatedAt: LocalDateTime = LocalDateTime.now()
                              )

/**
 * JSONB type for PCA coordinates stored as an array of 3 doubles.
 */
case class PcaCoordinatesJsonb(
                                x: Double,
                                y: Double,
                                z: Double
                              )

object PcaCoordinatesJsonb {
  implicit val format: OFormat[PcaCoordinatesJsonb] = Json.format[PcaCoordinatesJsonb]
}

/**
 * Represents a single population component in an ancestry breakdown.
 *
 * @param id                    Auto-generated primary key
 * @param populationBreakdownId Foreign key to parent population_breakdown
 * @param populationCode        Reference population code (e.g., CEU, YRI, CHB)
 * @param populationName        Human-readable population name
 * @param superPopulation       Continental grouping (e.g., European, African)
 * @param percentage            Ancestry percentage 0.0-100.0
 * @param confidenceLower       95% confidence interval lower bound
 * @param confidenceUpper       95% confidence interval upper bound
 * @param rank                  Display rank by percentage (1 = highest)
 */
case class PopulationComponent(
                                id: Option[Int] = None,
                                populationBreakdownId: Int,
                                populationCode: String,
                                populationName: Option[String] = None,
                                superPopulation: Option[String] = None,
                                percentage: Double,
                                confidenceLower: Option[Double] = None,
                                confidenceUpper: Option[Double] = None,
                                rank: Option[Int] = None
                              )

/**
 * Represents a super-population (continental level) summary.
 *
 * @param id                    Auto-generated primary key
 * @param populationBreakdownId Foreign key to parent population_breakdown
 * @param superPopulation       Continental grouping name
 * @param percentage            Combined percentage 0.0-100.0
 * @param populations           Array of contributing population codes
 */
case class SuperPopulationSummary(
                                   id: Option[Int] = None,
                                   populationBreakdownId: Int,
                                   superPopulation: String,
                                   percentage: Double,
                                   populations: Option[SuperPopulationListJsonb] = None
                                 )

/**
 * JSONB type for the list of populations in a super-population.
 */
case class SuperPopulationListJsonb(
                                     populations: Seq[String]
                                   )

object SuperPopulationListJsonb {
  implicit val format: OFormat[SuperPopulationListJsonb] = Json.format[SuperPopulationListJsonb]
}
