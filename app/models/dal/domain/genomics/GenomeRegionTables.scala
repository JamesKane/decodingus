package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.{GenomeRegion, GenomeRegionVersion, RegionCoordinate}
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
  def coordinates = column[JsValue]("coordinates")
  def properties = column[JsValue]("properties")

  def * = (id.?, regionType, name, coordinates, properties).<> (
    (t: (Option[Int], String, Option[String], JsValue, JsValue)) => GenomeRegion(
      t._1, t._2, t._3, t._4.as[Map[String, RegionCoordinate]], t._5
    ),
    (r: GenomeRegion) => Some((r.id, r.regionType, r.name, play.api.libs.json.Json.toJson(r.coordinates), r.properties))
  )
  
  // No Foreign Key to Contig anymore, as coordinates are embedded
}