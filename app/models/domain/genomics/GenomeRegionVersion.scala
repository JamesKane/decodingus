package models.domain.genomics

import java.time.Instant

/**
 * Tracks the data version for genome region data per reference build.
 * Used for ETag generation and cache invalidation.
 *
 * @param id              Optional unique identifier.
 * @param referenceGenome The canonical reference genome name (e.g., "GRCh37", "GRCh38", "hs1").
 * @param dataVersion     Semantic version string (e.g., "2024.12.1").
 * @param updatedAt       Timestamp when the version was last updated.
 */
case class GenomeRegionVersion(
  id: Option[Int] = None,
  referenceGenome: String,
  dataVersion: String,
  updatedAt: Instant
)
