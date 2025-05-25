package models.dal.domain.pangenome

import models.dal.MyPostgresProfile.api.*
import models.domain.pangenome.PangenomeAlignmentMetadata
import play.api.libs.json.JsValue
import slick.lifted.ProvenShape

import java.time.ZonedDateTime

class PangenomeAlignmentMetadataTable(tag: Tag) extends Table[PangenomeAlignmentMetadata](tag, "pangenome_alignment_metadata") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def sequenceFileId = column[Long]("sequence_file_id")
  def pangenomeGraphId = column[Int]("pangenome_graph_id")
  def metricLevel = column[String]("metric_level")
  def pangenomePathId = column[Option[Int]]("pangenome_path_id")
  def pangenomeNodeId = column[Option[Int]]("pangenome_node_id")
  def regionStartNodeId = column[Option[Int]]("region_start_node_id")
  def regionEndNodeId = column[Option[Int]]("region_end_node_id")
  def regionName = column[Option[String]]("region_name")
  def regionLengthBp = column[Option[Long]]("region_length_bp")
  def metricsDate = column[ZonedDateTime]("metrics_date")
  def analysisTool = column[String]("analysis_tool")
  def analysisToolVersion = column[Option[String]]("analysis_tool_version")
  def notes = column[Option[String]]("notes")
  def metadata = column[Option[JsValue]]("metadata")

  def * : ProvenShape[PangenomeAlignmentMetadata] = (
    id.?,
    sequenceFileId,
    pangenomeGraphId,
    metricLevel,
    pangenomePathId,
    pangenomeNodeId,
    regionStartNodeId,
    regionEndNodeId,
    regionName,
    regionLengthBp,
    metricsDate,
    analysisTool,
    analysisToolVersion,
    notes,
    metadata
  ).mapTo[PangenomeAlignmentMetadata]
}