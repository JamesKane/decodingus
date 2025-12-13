package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.{GenomeRegion, GenomeRegionVersion, RegionCoordinate, StrMarker}
import play.api.libs.json.JsValue
import java.time.Instant

/**
 * Slick table definition for genome_region_version table.
 * Tracks data versions for ETag generation.
 */
class GenomeRegionVersionTable(tag: Tag) extends Table[GenomeRegionVersion](tag, "genome_region_version") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def referenceGenome = column[String]("reference_genome", O.Unique)
  def dataVersion = column[String]("data_version")
  def updatedAt = column[Instant]("updated_at")

  def * = (id.?, referenceGenome, dataVersion, updatedAt).mapTo[GenomeRegionVersion]
}

/**
 * Slick table definition for genome_region_v2 table.
 * Stores structural regions (centromere, telomere, PAR, XTR, etc.) and Cytobands.
 * Supports multi-reference coordinates via JSONB.
 */
class GenomeRegionTable(tag: Tag) extends Table[GenomeRegion](tag, "genome_region_v2") {
  def id = column[Int]("region_id", O.PrimaryKey, O.AutoInc) // Column name changed to region_id
  def regionType = column[String]("region_type")
  def name = column[Option[String]]("name")
  def coordinates = column[Map[String, RegionCoordinate]]("coordinates")
  def properties = column[JsValue]("properties")

  def * = (id.?, regionType, name, coordinates, properties).mapTo[GenomeRegion]
  
  // No Foreign Key to Contig anymore, as coordinates are embedded
}

/**
 * Slick table definition for str_marker table.
 * Stores STR marker positions for Y-DNA analysis.
 */
class StrMarkerTable(tag: Tag) extends Table[StrMarker](tag, "str_marker") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def genbankContigId = column[Int]("genbank_contig_id")
  def name = column[String]("name")
  def startPos = column[Long]("start_pos")
  def endPos = column[Long]("end_pos")
  def period = column[Int]("period")
  def verified = column[Boolean]("verified")
  def note = column[Option[String]]("note")

  def * = (id.?, genbankContigId, name, startPos, endPos, period, verified, note).mapTo[StrMarker]

  def genbankContigFk = foreignKey("str_marker_genbank_contig_fk", genbankContigId,
    TableQuery[GenbankContigsTable])(_.genbankContigId, onDelete = ForeignKeyAction.Cascade)

  def idxContig = index("idx_str_marker_contig", genbankContigId)
}