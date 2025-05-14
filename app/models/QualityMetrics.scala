package models

case class QualityMetrics(
                           id: Option[Int],
                           contig: String,
                           startPos: Long,
                           endPos: Long,
                           numReads: Long,
                           refN: Long,
                           noCov: Long,
                           lowCov: Long,
                           excessiveCov: Long,
                           poorMq: Long,
                           callable: Long,
                           covPercent: Double,
                           meanDepth: Double,
                           meanMq: Double,
                           sequenceFileId: Long,
                         )