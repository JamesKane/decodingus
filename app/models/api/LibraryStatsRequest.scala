package models.api

import play.api.libs.json.{Json, OFormat}

case class CoverageStat(
                         contig: String,
                         num_reads: Long,
                         cov_bases: Long,
                         coverage_pct: Double,
                         mean_depth: Double,
                         mean_baseq: Double,
                         mean_mapq: Double,
                         callable_loci: Long,
                         no_coverage: Long,
                         low_coverage: Long,
                         poor_mapping_quality: Long
                       )

object CoverageStat {
  implicit val format: OFormat[CoverageStat] = Json.format[CoverageStat]
}

case class LibraryStatsRequest(
                                sample_id: String,
                                platform: String,
                                model: String,
                                reads: Long,
                                mapped_reads: Long,
                                read_len: Int,
                                insert_len: Double,
                                properly_paired_reads: Long,
                                files: Seq[FileInfo],
                                coverage: Seq[CoverageStat]
                              )

object LibraryStatsRequest {
  implicit val format: OFormat[LibraryStatsRequest] = Json.format[LibraryStatsRequest]
}
