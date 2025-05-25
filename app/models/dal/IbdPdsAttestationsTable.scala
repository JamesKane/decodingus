package models.dal

import models.IbdPdsAttestation
import models.dal.MyPostgresProfile.api.*
import java.time.ZonedDateTime
import java.util.UUID

class IbdPdsAttestationsTable(tag: Tag) extends Table[IbdPdsAttestation](tag, "ibd_pds_attestation") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def ibdDiscoveryIndexId = column[Long]("ibd_discovery_index_id")
  def attestingPdsGuid = column[UUID]("attesting_pds_guid")
  def attestingSampleGuid = column[UUID]("attesting_sample_guid")
  def attestationTimestamp = column[ZonedDateTime]("attestation_timestamp")
  def attestationSignature = column[String]("attestation_signature")
  def matchSummaryHash = column[String]("match_summary_hash")
  def attestationType = column[String]("attestation_type") // CHECK constraint handled by DB
  def attestationNotes = column[Option[String]]("attestation_notes")

  // Define the composite unique constraint
  def uniqueAttestation = index("idx_unique_pds_attestation", (ibdDiscoveryIndexId, attestingPdsGuid, attestationType), unique = true)

  def * = (
    id.?,
    ibdDiscoveryIndexId,
    attestingPdsGuid,
    attestingSampleGuid,
    attestationTimestamp,
    attestationSignature,
    matchSummaryHash,
    attestationType,
    attestationNotes
  ).mapTo[IbdPdsAttestation]
}