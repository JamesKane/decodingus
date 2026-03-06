package repositories

import jakarta.inject.Inject
import models.HaplogroupType
import models.domain.discovery.*
import play.api.Logging
import play.api.db.slick.DatabaseConfigProvider

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

trait ProposedBranchRepository {
  def create(pb: ProposedBranch): Future[ProposedBranch]
  def findById(id: Int): Future[Option[ProposedBranch]]
  def findByParentAndType(parentHaplogroupId: Int, haplogroupType: HaplogroupType): Future[Seq[ProposedBranch]]
  def findByStatus(status: ProposedBranchStatus, haplogroupType: Option[HaplogroupType] = None): Future[Seq[ProposedBranch]]
  def update(pb: ProposedBranch): Future[Boolean]
  def updateStatus(id: Int, status: ProposedBranchStatus): Future[Boolean]
  def updateConsensus(id: Int, consensusCount: Int, confidenceScore: Double): Future[Boolean]

  // Variant operations
  def addVariant(pbv: ProposedBranchVariant): Future[ProposedBranchVariant]
  def getVariants(proposedBranchId: Int): Future[Seq[ProposedBranchVariant]]
  def getVariantIds(proposedBranchId: Int): Future[Set[Int]]
  def updateVariantEvidence(proposedBranchId: Int, variantId: Int, evidenceCount: Int): Future[Boolean]

  // Evidence operations
  def addEvidence(evidence: ProposedBranchEvidence): Future[ProposedBranchEvidence]
  def getEvidence(proposedBranchId: Int): Future[Seq[ProposedBranchEvidence]]
  def countEvidence(proposedBranchId: Int): Future[Int]

  // Config
  def getConfig(haplogroupType: HaplogroupType, configKey: String): Future[Option[String]]
}

class ProposedBranchRepositoryImpl @Inject()(
  dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with ProposedBranchRepository
    with Logging {

  import models.dal.DatabaseSchema.domain.haplogroups.{proposedBranches, proposedBranchVariants, proposedBranchEvidence, discoveryConfig}
  import models.dal.MyPostgresProfile.api.*

  private def activeBranches = proposedBranches.filter(pb =>
    pb.status =!= (ProposedBranchStatus.Rejected: ProposedBranchStatus) &&
      pb.status =!= (ProposedBranchStatus.Promoted: ProposedBranchStatus)
  )

  override def create(pb: ProposedBranch): Future[ProposedBranch] = {
    val action = (proposedBranches returning proposedBranches.map(_.id)
      into ((row, id) => row.copy(id = Some(id)))) += pb
    runQuery(action)
  }

  override def findById(id: Int): Future[Option[ProposedBranch]] =
    runQuery(proposedBranches.filter(_.id === id).result.headOption)

  override def findByParentAndType(parentHaplogroupId: Int, haplogroupType: HaplogroupType): Future[Seq[ProposedBranch]] =
    runQuery(activeBranches
      .filter(pb => pb.parentHaplogroupId === parentHaplogroupId && pb.haplogroupType === haplogroupType)
      .result)

  override def findByStatus(status: ProposedBranchStatus, haplogroupType: Option[HaplogroupType]): Future[Seq[ProposedBranch]] = {
    val base = proposedBranches.filter(_.status === status)
    val filtered = haplogroupType.fold(base)(ht => base.filter(_.haplogroupType === ht))
    runQuery(filtered.result)
  }

  override def update(pb: ProposedBranch): Future[Boolean] =
    runQuery(proposedBranches.filter(_.id === pb.id.get)
      .map(r => (r.proposedName, r.status, r.consensusCount, r.confidenceScore, r.updatedAt, r.reviewedAt, r.reviewedBy, r.notes, r.promotedHaplogroupId))
      .update((pb.proposedName, pb.status, pb.consensusCount, pb.confidenceScore, LocalDateTime.now(), pb.reviewedAt, pb.reviewedBy, pb.notes, pb.promotedHaplogroupId))
      .map(_ > 0))

  override def updateStatus(id: Int, status: ProposedBranchStatus): Future[Boolean] =
    runQuery(proposedBranches.filter(_.id === id).map(r => (r.status, r.updatedAt)).update((status, LocalDateTime.now())).map(_ > 0))

  override def updateConsensus(id: Int, consensusCount: Int, confidenceScore: Double): Future[Boolean] =
    runQuery(proposedBranches.filter(_.id === id)
      .map(r => (r.consensusCount, r.confidenceScore, r.updatedAt))
      .update((consensusCount, confidenceScore, LocalDateTime.now()))
      .map(_ > 0))

  // Variant operations
  override def addVariant(pbv: ProposedBranchVariant): Future[ProposedBranchVariant] = {
    val action = (proposedBranchVariants returning proposedBranchVariants.map(_.id)
      into ((row, id) => row.copy(id = Some(id)))) += pbv
    runQuery(action)
  }

  override def getVariants(proposedBranchId: Int): Future[Seq[ProposedBranchVariant]] =
    runQuery(proposedBranchVariants.filter(_.proposedBranchId === proposedBranchId).result)

  override def getVariantIds(proposedBranchId: Int): Future[Set[Int]] =
    runQuery(proposedBranchVariants.filter(_.proposedBranchId === proposedBranchId).map(_.variantId).result).map(_.toSet)

  override def updateVariantEvidence(proposedBranchId: Int, variantId: Int, evidenceCount: Int): Future[Boolean] =
    runQuery(proposedBranchVariants
      .filter(pbv => pbv.proposedBranchId === proposedBranchId && pbv.variantId === variantId)
      .map(r => (r.evidenceCount, r.lastObservedAt))
      .update((evidenceCount, LocalDateTime.now()))
      .map(_ > 0))

  // Evidence operations
  override def addEvidence(evidence: ProposedBranchEvidence): Future[ProposedBranchEvidence] = {
    val action = (proposedBranchEvidence returning proposedBranchEvidence.map(_.id)
      into ((row, id) => row.copy(id = Some(id)))) += evidence
    runQuery(action)
  }

  override def getEvidence(proposedBranchId: Int): Future[Seq[ProposedBranchEvidence]] =
    runQuery(proposedBranchEvidence.filter(_.proposedBranchId === proposedBranchId).result)

  override def countEvidence(proposedBranchId: Int): Future[Int] =
    runQuery(proposedBranchEvidence.filter(_.proposedBranchId === proposedBranchId).length.result)

  // Config
  override def getConfig(haplogroupType: HaplogroupType, configKey: String): Future[Option[String]] =
    runQuery(discoveryConfig
      .filter(c => c.haplogroupType === haplogroupType && c.configKey === configKey)
      .map(_.configValue)
      .result.headOption)
}
