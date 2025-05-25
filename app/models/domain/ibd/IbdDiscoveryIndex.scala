package models.domain.ibd

import java.time.ZonedDateTime
import java.util.UUID

case class IbdDiscoveryIndex(
                              id: Option[Long],
                              sampleGuid1: UUID,
                              sampleGuid2: UUID,
                              pangenomeGraphId: Int,
                              matchRegionType: String,
                              totalSharedCmApprox: Option[Double],
                              numSharedSegmentsApprox: Option[Int],
                              isPubliclyDiscoverable: Boolean,
                              consensusStatus: String,
                              lastConsensusUpdate: ZonedDateTime,
                              validationServiceGuid: Option[UUID],
                              validationTimestamp: Option[ZonedDateTime],
                              indexedByService: Option[String],
                              indexedDate: ZonedDateTime
                            )