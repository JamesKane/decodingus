package models.domain.ibd

import java.time.ZonedDateTime
import java.util.UUID

case class PopulationOverlapScore(
                                   id: Option[Long],
                                   sampleGuid1: UUID,
                                   sampleGuid2: UUID,
                                   overlapScore: Double,
                                   computedAt: ZonedDateTime
                                 )
