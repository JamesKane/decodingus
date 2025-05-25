package models

import java.time.ZonedDateTime
import java.util.UUID

case class IbdPdsAttestation(
                              id: Option[Long],
                              ibdDiscoveryIndexId: Long,
                              attestingPdsGuid: UUID,
                              attestingSampleGuid: UUID,
                              attestationTimestamp: ZonedDateTime,
                              attestationSignature: String,
                              matchSummaryHash: String,
                              attestationType: String,
                              attestationNotes: Option[String]
                            )