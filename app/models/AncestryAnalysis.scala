package models

import java.util.UUID

case class AncestryAnalysis(
                             id: Option[Int],
                             sampleGuid: UUID,
                             analysisMethodId: Int,
                             populationId: Int,
                             probability: Double
                           )
