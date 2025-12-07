package models.domain.genomics

import java.util.UUID

/**
 * A case class representing the results of an ancestry analysis.
 *
 * @param id               An optional unique identifier for the analysis.
 * @param sampleGuid       A universally unique identifier (UUID) representing the sample the analysis pertains to.
 * @param analysisMethodId The identifier of the analysis method used in determining the ancestry.
 * @param populationId     The identifier of the population determined or analyzed in this study.
 * @param probability      A double representing the probability that the sample belongs to the specified population.
 */
case class AncestryAnalysis(id: Option[Int], sampleGuid: UUID, analysisMethodId: Int,
                            populationId: Int, probability: Double)
