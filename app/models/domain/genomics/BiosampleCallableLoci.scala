package models.domain.genomics

import java.time.LocalDateTime
import java.util.UUID

case class BiosampleCallableLoci(
  id: Option[Int] = None,
  sampleType: String,
  sampleId: Int,
  sampleGuid: Option[UUID],
  chromosome: String,
  totalCallableBp: Long,
  regionCount: Option[Int],
  bedFileHash: Option[String],
  computedAt: LocalDateTime,
  sourceTestTypeId: Option[Int],
  yXdegenCallableBp: Option[Long],
  yAmpliconicCallableBp: Option[Long],
  yPalindromicCallableBp: Option[Long]
)
