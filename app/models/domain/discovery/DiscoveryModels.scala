package models.domain.discovery

import models.HaplogroupType
import play.api.libs.json.{JsValue, Json, OFormat}

import java.time.LocalDateTime
import java.util.UUID

case class BiosamplePrivateVariant(
  id: Option[Int] = None,
  sampleType: BiosampleSourceType,
  sampleId: Int,
  sampleGuid: UUID,
  variantId: Int,
  haplogroupType: HaplogroupType,
  terminalHaplogroupId: Int,
  discoveredAt: LocalDateTime = LocalDateTime.now(),
  status: PrivateVariantStatus = PrivateVariantStatus.Active
)

object BiosamplePrivateVariant {
  implicit val format: OFormat[BiosamplePrivateVariant] = Json.format
}

case class ProposedBranch(
  id: Option[Int] = None,
  parentHaplogroupId: Int,
  proposedName: Option[String] = None,
  haplogroupType: HaplogroupType,
  status: ProposedBranchStatus = ProposedBranchStatus.Pending,
  consensusCount: Int = 0,
  confidenceScore: Double = 0.0,
  createdAt: LocalDateTime = LocalDateTime.now(),
  updatedAt: LocalDateTime = LocalDateTime.now(),
  reviewedAt: Option[LocalDateTime] = None,
  reviewedBy: Option[String] = None,
  notes: Option[String] = None,
  promotedHaplogroupId: Option[Int] = None
)

object ProposedBranch {
  implicit val format: OFormat[ProposedBranch] = Json.format
}

case class ProposedBranchVariant(
  id: Option[Int] = None,
  proposedBranchId: Int,
  variantId: Int,
  isDefining: Boolean = true,
  evidenceCount: Int = 1,
  firstObservedAt: LocalDateTime = LocalDateTime.now(),
  lastObservedAt: LocalDateTime = LocalDateTime.now()
)

object ProposedBranchVariant {
  implicit val format: OFormat[ProposedBranchVariant] = Json.format
}

case class ProposedBranchEvidence(
  id: Option[Int] = None,
  proposedBranchId: Int,
  sampleType: BiosampleSourceType,
  sampleId: Int,
  sampleGuid: UUID,
  addedAt: LocalDateTime = LocalDateTime.now(),
  variantMatchCount: Int = 0,
  variantMismatchCount: Int = 0
)

object ProposedBranchEvidence {
  implicit val format: OFormat[ProposedBranchEvidence] = Json.format
}

case class CuratorAction(
  id: Option[Int] = None,
  curatorId: String,
  actionType: CuratorActionType,
  targetType: CuratorTargetType,
  targetId: Int,
  previousState: Option[JsValue] = None,
  newState: Option[JsValue] = None,
  reason: Option[String] = None,
  createdAt: LocalDateTime = LocalDateTime.now()
)

object CuratorAction {
  implicit val format: OFormat[CuratorAction] = Json.format
}

case class DiscoveryConfig(
  id: Option[Int] = None,
  haplogroupType: HaplogroupType,
  configKey: String,
  configValue: String,
  description: Option[String] = None,
  updatedAt: LocalDateTime = LocalDateTime.now(),
  updatedBy: Option[String] = None
)

object DiscoveryConfig {
  implicit val format: OFormat[DiscoveryConfig] = Json.format
}
