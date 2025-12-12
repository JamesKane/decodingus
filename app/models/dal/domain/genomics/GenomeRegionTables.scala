package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.{Cytoband, GenomeRegion, GenomeRegionVersion, StrMarker}

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
 * Slick table definition for genome_region table.
 * Stores structural regions (centromere, telomere, PAR, XTR, etc.).
 */
class GenomeRegionTable(tag: Tag) extends Table[GenomeRegion](tag, "genome_region") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def genbankContigId = column[Int]("genbank_contig_id")
  def regionType = column[String]("region_type")
  def name = column[Option[String]]("name")
  def startPos = column[Long]("start_pos")
  def endPos = column[Long]("end_pos")
  def modifier = column[Option[BigDecimal]]("modifier")

  def * = (id.?, genbankContigId, regionType, name, startPos, endPos, modifier).mapTo[GenomeRegion]

  def genbankContigFk = foreignKey("genome_region_genbank_contig_fk", genbankContigId,
    TableQuery[GenbankContigsTable])(_.genbankContigId, onDelete = ForeignKeyAction.Cascade)

  def idxContig = index("idx_genome_region_contig", genbankContigId)
}

/**
 * Slick table definition for cytoband table.
 * Stores cytoband annotations for chromosome ideogram display.
 */
class CytobandTable(tag: Tag) extends Table[Cytoband](tag, "cytoband") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def genbankContigId = column[Int]("genbank_contig_id")
  def name = column[String]("name")
  def startPos = column[Long]("start_pos")
  def endPos = column[Long]("end_pos")
  def stain = column[String]("stain")

  def * = (id.?, genbankContigId, name, startPos, endPos, stain).mapTo[Cytoband]

  def genbankContigFk = foreignKey("cytoband_genbank_contig_fk", genbankContigId,
    TableQuery[GenbankContigsTable])(_.genbankContigId, onDelete = ForeignKeyAction.Cascade)

  def idxContig = index("idx_cytoband_contig", genbankContigId)
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
