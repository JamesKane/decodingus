package models.domain.pangenome

import play.api.libs.json.JsValue

import java.time.ZonedDateTime

case class PangenomeAlignmentMetadata(
                                       id: Option[Long],
                                       sequenceFileId: Long,
                                       pangenomeGraphId: Int,
                                       metricLevel: String,
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