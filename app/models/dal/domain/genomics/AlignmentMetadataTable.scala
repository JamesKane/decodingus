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

  def referenceBuild = column[Option[String]]("reference_build")

  def variantCaller = column[Option[String]]("variant_caller")

  def genomeTerritory = column[Option[Long]]("genome_territory")

  def meanCoverage = column[Option[Double]]("mean_coverage")

  def medianCoverage = column[Option[Double]]("median_coverage")

  def sdCoverage = column[Option[Double]]("sd_coverage")

  def pctExcDupe = column[Option[Double]]("pct_exc_dupe")

  def pctExcMapq = column[Option[Double]]("pct_exc_mapq")

  def pct10x = column[Option[Double]]("pct_10x")

  def pct20x = column[Option[Double]]("pct_20x")

  def pct30x = column[Option[Double]]("pct_30x")

  def hetSnpSensitivity = column[Option[Double]]("het_snp_sensitivity")

  def metricsDate = column[LocalDateTime]("metrics_date")

  def analysisTool = column[String]("analysis_tool")

  def analysisToolVersion = column[Option[String]]("analysis_tool_version")

  def notes = column[Option[String]]("notes")

  def metadata = column[Option[JsValue]]("metadata")

  def * = (
    (id.?, sequenceFileId, genbankContigId, metricLevel),
    (regionName, regionStartPos, regionEndPos, regionLengthBp),
    (referenceBuild, variantCaller, genomeTerritory, meanCoverage, medianCoverage, sdCoverage, pctExcDupe, pctExcMapq, pct10x, pct20x, pct30x, hetSnpSensitivity),
    (metricsDate, analysisTool, analysisToolVersion, notes, metadata)
  ).shaped <> ( {
    case ((id, seqId, contigId, lvl), (rName, rStart, rEnd, rLen), (refBuild, vCaller, gTerr, meanCov, medCov, sdCov, pDupe, pMapq, p10, p20, p30, hetSens), (mDate, tool, toolVer, notes, meta)) =>
      AlignmentMetadata(
        id, seqId, contigId, lvl,
        rName, rStart, rEnd, rLen,
        refBuild, vCaller, gTerr, meanCov, medCov, sdCov, pDupe, pMapq, p10, p20, p30, hetSens,
        mDate, tool, toolVer, notes, meta
      )
  }, { (m: AlignmentMetadata) =>
    Some((
      (m.id, m.sequenceFileId, m.genbankContigId, m.metricLevel),
      (m.regionName, m.regionStartPos, m.regionEndPos, m.regionLengthBp),
      (m.referenceBuild, m.variantCaller, m.genomeTerritory, m.meanCoverage, m.medianCoverage, m.sdCoverage, m.pctExcDupe, m.pctExcMapq, m.pct10x, m.pct20x, m.pct30x, m.hetSnpSensitivity),
      (m.metricsDate, m.analysisTool, m.analysisToolVersion, m.notes, m.metadata)
    ))
  }
  )

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