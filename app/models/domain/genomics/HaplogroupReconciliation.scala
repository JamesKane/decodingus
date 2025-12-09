package models.domain.genomics

import play.api.libs.json.*

import java.time.LocalDateTime

/**
 * DNA type enum for reconciliation records.
 */
enum DnaType {
  case Y_DNA, MT_DNA
}

object DnaType {
  def fromString(s: String): Option[DnaType] = s.toUpperCase match {
    case "Y_DNA" => Some(Y_DNA)
    case "MT_DNA" => Some(MT_DNA)
    case _ => None
  }

  implicit val format: Format[DnaType] = new Format[DnaType] {
    def reads(json: JsValue): JsResult[DnaType] = json match {
      case JsString(s) => fromString(s).map(JsSuccess(_)).getOrElse(JsError(s"Unknown DnaType: $s"))
      case _ => JsError("String value expected")
    }

    def writes(dt: DnaType): JsValue = JsString(dt.toString)
  }
}

/**
 * Compatibility level enum for reconciliation status.
 */
enum CompatibilityLevel {
  case COMPATIBLE, MINOR_DIVERGENCE, MAJOR_DIVERGENCE, INCOMPATIBLE
}

object CompatibilityLevel {
  def fromString(s: String): Option[CompatibilityLevel] = s.toUpperCase match {
    case "COMPATIBLE" => Some(COMPATIBLE)
    case "MINOR_DIVERGENCE" => Some(MINOR_DIVERGENCE)
    case "MAJOR_DIVERGENCE" => Some(MAJOR_DIVERGENCE)
    case "INCOMPATIBLE" => Some(INCOMPATIBLE)
    case _ => None
  }

  implicit val format: Format[CompatibilityLevel] = new Format[CompatibilityLevel] {
    def reads(json: JsValue): JsResult[CompatibilityLevel] = json match {
      case JsString(s) => fromString(s).map(JsSuccess(_)).getOrElse(JsError(s"Unknown CompatibilityLevel: $s"))
      case _ => JsError("String value expected")
    }

    def writes(cl: CompatibilityLevel): JsValue = JsString(cl.toString)
  }
}

/**
 * Reconciliation status metrics - stored as JSONB to reduce tuple size.
 * Contains: compatibilityLevel, consensusHaplogroup, statusConfidence,
 * branchCompatibilityScore, snpConcordance, runCount, warnings
 */
case class ReconciliationStatus(
  compatibilityLevel: Option[String] = None,  // COMPATIBLE, MINOR_DIVERGENCE, MAJOR_DIVERGENCE, INCOMPATIBLE
  consensusHaplogroup: Option[String] = None,
  statusConfidence: Option[Double] = None,
  branchCompatibilityScore: Option[Double] = None,
  snpConcordance: Option[Double] = None,
  runCount: Option[Int] = None,
  warnings: Option[Seq[String]] = None
)

object ReconciliationStatus {
  implicit val format: Format[ReconciliationStatus] = Json.format[ReconciliationStatus]
}

/**
 * Represents haplogroup reconciliation results for a specimen donor.
 * Stored at the donor level since a donor may have multiple biosamples/runs.
 *
 * @param id                        Auto-generated primary key
 * @param atUri                     AT URI for this record
 * @param atCid                     Content identifier for version tracking
 * @param specimenDonorId           Foreign key to specimen_donor
 * @param dnaType                   Y_DNA or MT_DNA
 * @param status                    Reconciliation status metrics (JSONB)
 * @param runCalls                  Array of RunHaplogroupCall objects (stored as JSONB)
 * @param snpConflicts              Array of SnpConflict objects (stored as JSONB)
 * @param heteroplasmyObservations  Array of HeteroplasmyObservation objects (stored as JSONB)
 * @param identityVerification      Identity verification metrics (stored as JSONB)
 * @param manualOverride            Manual override if user corrected the consensus (stored as JSONB)
 * @param auditLog                  Audit log of reconciliation changes (stored as JSONB)
 * @param lastReconciliationAt      When reconciliation was last performed
 * @param deleted                   Soft delete flag
 * @param createdAt                 Record creation timestamp
 * @param updatedAt                 Record update timestamp
 */
case class HaplogroupReconciliation(
                                     id: Option[Int] = None,
                                     atUri: Option[String] = None,
                                     atCid: Option[String] = None,
                                     specimenDonorId: Int,
                                     dnaType: DnaType,
                                     // Status metrics consolidated into JSONB
                                     status: ReconciliationStatus = ReconciliationStatus(),
                                     // JSONB fields stored as play.api.libs.json.JsValue
                                     runCalls: JsValue,  // Required: array of RunHaplogroupCall
                                     snpConflicts: Option[JsValue] = None,
                                     heteroplasmyObservations: Option[JsValue] = None,
                                     identityVerification: Option[JsValue] = None,
                                     manualOverride: Option[JsValue] = None,
                                     auditLog: Option[JsValue] = None,
                                     lastReconciliationAt: Option[LocalDateTime] = None,
                                     deleted: Boolean = false,
                                     createdAt: LocalDateTime = LocalDateTime.now(),
                                     updatedAt: LocalDateTime = LocalDateTime.now()
                                   )
