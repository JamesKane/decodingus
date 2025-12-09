package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.{DnaType, HaplogroupReconciliation, ReconciliationStatus}
import play.api.libs.json.JsValue

import java.time.LocalDateTime

/**
 * Slick table definition for haplogroup_reconciliation table.
 * Stores multi-run haplogroup reconciliation at specimen donor level.
 */
class HaplogroupReconciliationTable(tag: Tag) extends Table[HaplogroupReconciliation](tag, "haplogroup_reconciliation") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def atUri = column[Option[String]]("at_uri")
  def atCid = column[Option[String]]("at_cid")
  def specimenDonorId = column[Int]("specimen_donor_id")
  def dnaType = column[DnaType]("dna_type")
  // JSONB column for status metrics
  def status = column[ReconciliationStatus]("status")
  // JSONB columns stored as JsValue
  def runCalls = column[JsValue]("run_calls")
  def snpConflicts = column[Option[JsValue]]("snp_conflicts")
  def heteroplasmyObservations = column[Option[JsValue]]("heteroplasmy_observations")
  def identityVerification = column[Option[JsValue]]("identity_verification")
  def manualOverride = column[Option[JsValue]]("manual_override")
  def auditLog = column[Option[JsValue]]("audit_log")
  def lastReconciliationAt = column[Option[LocalDateTime]]("last_reconciliation_at")
  def deleted = column[Boolean]("deleted")
  def createdAt = column[LocalDateTime]("created_at")
  def updatedAt = column[LocalDateTime]("updated_at")

  // 16 fields - under the 22 tuple limit
  def * = (
    id.?,
    atUri,
    atCid,
    specimenDonorId,
    dnaType,
    status,
    runCalls,
    snpConflicts,
    heteroplasmyObservations,
    identityVerification,
    manualOverride,
    auditLog,
    lastReconciliationAt,
    deleted,
    createdAt,
    updatedAt
  ).mapTo[HaplogroupReconciliation]

  // Indexes
  def atUriIdx = index("idx_reconciliation_at_uri", atUri, unique = true)
  def specimenDonorIdx = index("idx_reconciliation_specimen_donor", specimenDonorId)

  // Foreign key to specimen_donor
  def specimenDonorFk = foreignKey(
    "fk_reconciliation_specimen_donor",
    specimenDonorId,
    TableQuery[SpecimenDonorsTable]
  )(_.id, onDelete = ForeignKeyAction.Cascade)
}
