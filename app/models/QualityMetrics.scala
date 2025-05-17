package models

/**
 * Represents quality metrics for a genomic region derived from sequencing data.
 *
 * @param id             An optional unique identifier for the metrics entry, used for internal tracking purposes.
 * @param contig         The name of the contig or reference sequence being analyzed.
 * @param startPos       The starting position of the genomic region for which metrics are calculated.
 * @param endPos         The ending position of the genomic region for which metrics are calculated.
 * @param numReads       The total number of reads mapped to the genomic region.
 * @param refN           The count of reference bases in the genomic region that are 'N'.
 * @param noCov          The number of bases in the region that have no coverage (0 reads).
 * @param lowCov         The number of bases in the region with coverage below a defined threshold.
 * @param excessiveCov   The number of bases in the region with excessively high coverage.
 * @param poorMq         The number of bases in the region with poor mapping quality.
 * @param callable       The number of bases in the region deemed callable (sufficient quality and coverage).
 * @param covPercent     The percentage of the region covered by sufficient reads.
 * @param meanDepth      The mean depth of coverage for the genomic region.
 * @param meanMq         The mean mapping quality for the reads in the genomic region.
 * @param sequenceFileId The identifier of the sequencing file associated with these metrics.
 */
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