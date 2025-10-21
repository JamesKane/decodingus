package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.{AlignmentMetadata, MetricLevel}
import play.api.libs.json.JsValue

import java.time.LocalDateTime

/**
 * Slick table definition for alignment_metadata table.
 */
class AlignmentMetadataTable(tag: Tag) extends Table[AlignmentMetadata](tag, Some("public"), "alignment_metadata") {

  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def sequenceFileId = column[Long]("sequence_file_id")
  def genbankContigId = column[Int]("genbank_contig_id")
  def metricLevel = column[MetricLevel]("metric_level")
  def regionName = column[Option[String]]("region_name")
  def regionStartPos = column[Option[Long]]("region_start_pos")
  def regionEndPos = column[Option[Long]]("region_end_pos")
  def regionLengthBp = column[Option[Long]]("region_length_bp")
  def metricsDate = column[LocalDateTime]("metrics_date")
  def analysisTool = column[String]("analysis_tool")
  def analysisToolVersion = column[Option[String]]("analysis_tool_version")
  def notes = column[Option[String]]("notes")
  def metadata = column[Option[JsValue]]("metadata")

  def * = (
    id.?,
    sequenceFileId,
    genbankContigId,
    metricLevel,
    regionName,
    regionStartPos,
    regionEndPos,
    regionLengthBp,
    metricsDate,
    analysisTool,
    analysisToolVersion,
    notes,
    metadata
  ) <> ((AlignmentMetadata.apply _).tupled, AlignmentMetadata.unapply)

  // Foreign key constraints
  def sequenceFileFk = foreignKey("alignment_metadata_sequence_file_fk", sequenceFileId,
    TableQuery[SequenceFilesTable])(_.id.asInstanceOf[Rep[Long]], onDelete = ForeignKeyAction.Cascade)

  def genbankContigFk = foreignKey("alignment_metadata_genbank_contig_fk", genbankContigId,
    TableQuery[GenbankContigsTable])(_.genbankContigId, onDelete = ForeignKeyAction.Cascade)

  // Indices
  def idxSequenceFile = index("idx_alignment_metadata_sequence_file", sequenceFileId)
  def idxGenbankContig = index("idx_alignment_metadata_genbank_contig", genbankContigId)
  def idxMetricLevel = index("idx_alignment_metadata_metric_level", metricLevel)
}