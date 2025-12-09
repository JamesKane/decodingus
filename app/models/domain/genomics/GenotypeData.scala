package models.domain.genomics

import models.atmosphere.FileInfo
import play.api.libs.json.*

import java.time.LocalDateTime
import java.util.UUID

/**
 * Genotype metrics - stored as JSONB to reduce tuple size.
 * Contains quality metrics, dates, and haplogroup calls.
 */
case class GenotypeMetrics(
  // Quality metrics
  totalMarkersCalled: Option[Int] = None,
  totalMarkersPossible: Option[Int] = None,
  callRate: Option[Double] = None,
  noCallRate: Option[Double] = None,
  yMarkersCalled: Option[Int] = None,
  yMarkersTotal: Option[Int] = None,
  mtMarkersCalled: Option[Int] = None,
  mtMarkersTotal: Option[Int] = None,
  autosomalMarkersCalled: Option[Int] = None,
  hetRate: Option[Double] = None,
  // Dates
  testDate: Option[LocalDateTime] = None,
  processedAt: Option[LocalDateTime] = None,
  // Derived haplogroups
  derivedYHaplogroup: Option[HaplogroupResult] = None,
  derivedMtHaplogroup: Option[HaplogroupResult] = None,
  // Files
  files: Option[Seq[FileInfo]] = None
)

object GenotypeMetrics {
  implicit val format: Format[GenotypeMetrics] = Json.format[GenotypeMetrics]
}

/**
 * Represents genotype data from SNP array/chip testing.
 *
 * @param id                     Auto-generated primary key
 * @param atUri                  AT URI for this record (for citizen-owned data)
 * @param atCid                  Content identifier for version tracking
 * @param sampleGuid             UUID of the associated sample
 * @param testTypeId             Foreign key to test_type_definition
 * @param provider               Testing provider (e.g., 23andMe, AncestryDNA)
 * @param chipVersion            Chip version identifier
 * @param buildVersion           Genome build version (GRCh37, GRCh38)
 * @param sourceFileHash         SHA-256 for deduplication
 * @param metrics                All quality metrics, dates, haplogroups, files (JSONB)
 * @param populationBreakdownId  Foreign key to population_breakdown
 * @param deleted                Soft delete flag
 * @param createdAt              Record creation timestamp
 * @param updatedAt              Record update timestamp
 */
case class GenotypeData(
                         id: Option[Int] = None,
                         atUri: Option[String] = None,
                         atCid: Option[String] = None,
                         sampleGuid: UUID,
                         testTypeId: Option[Int] = None,
                         provider: Option[String] = None,
                         chipVersion: Option[String] = None,
                         buildVersion: Option[String] = None,
                         sourceFileHash: Option[String] = None,
                         metrics: GenotypeMetrics = GenotypeMetrics(),
                         populationBreakdownId: Option[Int] = None,
                         deleted: Boolean = false,
                         createdAt: LocalDateTime = LocalDateTime.now(),
                         updatedAt: LocalDateTime = LocalDateTime.now()
                       )
