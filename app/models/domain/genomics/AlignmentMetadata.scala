package models.domain.genomics

import play.api.libs.json.{JsValue, Json, OFormat}

import java.time.LocalDateTime

/**
 * Represents the scope level at which alignment metrics are calculated.
 */
enum MetricLevel {
  case CONTIG_OVERALL  // Metrics for entire contig
  case REGION          // Metrics for specific coordinate ranges
}

object MetricLevel {
  import play.api.libs.json._

  // Use Format instead of OFormat since we're dealing with enums (simple values, not objects)
  implicit val format: Format[MetricLevel] = new Format[MetricLevel] {
    def reads(json: JsValue): JsResult[MetricLevel] = json match {
      case JsString(s) =>
        try {
          JsSuccess(MetricLevel.valueOf(s))
        } catch {
          case _: IllegalArgumentException => JsError(s"Unknown MetricLevel: $s")
        }
      case _ => JsError("String value expected")
    }

    def writes(level: MetricLevel): JsValue = JsString(level.toString)
  }
}

/**
 * Represents metadata about alignment statistics for a sequence file aligned to a linear reference.
 *
 * @param id                  Unique identifier for the metadata record
 * @param sequenceFileId      Foreign key to the sequence file
 * @param genbankContigId     Foreign key to the GenBank contig (linear reference)
 * @param metricLevel         Scope of the metrics (overall contig or specific region)
 * @param regionName          Optional name for the region (e.g., "Chromosome X", "Gene ABC")
 * @param regionStartPos      Start position for regional metrics (1-based, inclusive)
 * @param regionEndPos        End position for regional metrics (1-based, inclusive)
 * @param regionLengthBp      Length of the region in base pairs
 * @param metricsDate         Timestamp when metrics were calculated
 * @param analysisTool        Tool used to generate metrics (e.g., "samtools", "mosdepth")
 * @param analysisToolVersion Version of the analysis tool
 * @param notes               Optional additional notes
 * @param metadata            Optional JSON metadata for tool-specific information
 */
case class AlignmentMetadata(
                              id: Option[Long] = None,
                              sequenceFileId: Long,
                              genbankContigId: Int,
                              metricLevel: MetricLevel,
                              regionName: Option[String] = None,
                              regionStartPos: Option[Long] = None,
                              regionEndPos: Option[Long] = None,
                              regionLengthBp: Option[Long] = None,
                              metricsDate: LocalDateTime = LocalDateTime.now(),
                              analysisTool: String,
                              analysisToolVersion: Option[String] = None,
                              notes: Option[String] = None,
                              metadata: Option[JsValue] = None
                            )

object AlignmentMetadata {
  implicit val format: OFormat[AlignmentMetadata] = Json.format[AlignmentMetadata]
}