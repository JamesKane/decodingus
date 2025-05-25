package models.dal.domain.pangenome

import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api.*
import models.domain.pangenome.ReportedVariantPangenome
import play.api.libs.json.JsValue

import java.time.ZonedDateTime
import java.util.UUID

class ReportedVariantPangenomesTable(tag: Tag) extends Table[ReportedVariantPangenome](tag, "reported_variant_pangenome") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)

  def sampleGuid = column[UUID]("sample_guid")

  def graphId = column[Int]("graph_id")

  def variantType = column[String]("variant_type")

  def referencePathId = column[Option[Int]]("reference_path_id")

  def referenceStartPosition = column[Option[Int]]("reference_start_position")

  def referenceEndPosition = column[Option[Int]]("reference_end_position")

  def variantNodes = column[List[Int]]("variant_nodes")(MyPostgresProfile.api.intListTypeMapper)

  def variantEdges = column[List[Int]]("variant_edges")(MyPostgresProfile.api.intListTypeMapper)

  def alternateAlleleSequence = column[Option[String]]("alternate_allele_sequence")

  def referenceAlleleSequence = column[Option[String]]("reference_allele_sequence")

  def referenceRepeatCount = column[Option[Int]]("reference_repeat_count")

  def alternateRepeatCount = column[Option[Int]]("alternate_repeat_count")

  def alleleFraction = column[Option[Double]]("allele_fraction")

  def depth = column[Option[Int]]("depth")

  def reportedDate = column[ZonedDateTime]("reported_date")

  def provenance = column[String]("provenance")

  def confidenceScore = column[Double]("confidence_score")

  def notes = column[Option[String]]("notes")

  def status = column[String]("status")

  def zygosity = column[Option[String]]("zygosity") // CHECK constraint handled by DB

  def haplotypeInformation = column[Option[JsValue]]("haplotype_information") // JSONB

  def * = (
    id.?,
    sampleGuid,
    graphId,
    variantType,
    referencePathId,
    referenceStartPosition,
    referenceEndPosition,
    variantNodes,
    variantEdges,
    alternateAlleleSequence,
    referenceAlleleSequence,
    referenceRepeatCount,
    alternateRepeatCount,
    alleleFraction,
    depth,
    reportedDate,
    provenance,
    confidenceScore,
    notes,
    status,
    zygosity,
    haplotypeInformation
  ).mapTo[ReportedVariantPangenome]
}