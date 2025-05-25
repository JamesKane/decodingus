package models

import java.time.ZonedDateTime
import play.api.libs.json.JsValue

case class PangenomeAlignmentMetadata(
                                       id: Option[Long], // Primary key
                                       sequenceFileId: Long,
                                       pangenomeGraphId: Int,
                                       metricLevel: String, // CHECK constraint handled by DB
                                       pangenomePathId: Option[Int],
                                       pangenomeNodeId: Option[Int],
                                       regionStartNodeId: Option[Int],
                                       regionEndNodeId: Option[Int],
                                       regionName: Option[String],
                                       regionLengthBp: Option[Long],
                                       metricsDate: ZonedDateTime,
                                       analysisTool: String,
                                       analysisToolVersion: Option[String],
                                       notes: Option[String],
                                       metadata: Option[JsValue]
                                     )