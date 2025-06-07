package models.domain.pangenome

case class PangenomeAlignmentCoverage(
                                       alignmentMetadataId: Long,
                                       meanDepth: Option[Double],
                                       medianDepth: Option[Double],
                                       percentCoverageAt1x: Option[Double],
                                       percentCoverageAt5x: Option[Double],
                                       percentCoverageAt10x: Option[Double],
                                       percentCoverageAt20x: Option[Double],
                                       percentCoverageAt30x: Option[Double],
                                       basesNoCoverage: Option[Long],
                                       basesLowQualityMapping: Option[Long],
                                       basesCallable: Option[Long],
                                       meanMappingQuality: Option[Double]
                                     )
