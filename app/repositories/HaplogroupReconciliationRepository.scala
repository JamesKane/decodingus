package repositories

import models.domain.genomics.{DnaType, HaplogroupReconciliation}

import scala.concurrent.Future

trait HaplogroupReconciliationRepository {
  def findById(id: Int): Future[Option[HaplogroupReconciliation]]
  def findByAtUri(atUri: String): Future[Option[HaplogroupReconciliation]]
  def findBySpecimenDonorId(specimenDonorId: Int): Future[Seq[HaplogroupReconciliation]]
  def findBySpecimenDonorIdAndDnaType(specimenDonorId: Int, dnaType: DnaType): Future[Option[HaplogroupReconciliation]]
  def findByConsensusHaplogroup(haplogroup: String): Future[Seq[HaplogroupReconciliation]]
  def create(reconciliation: HaplogroupReconciliation): Future[HaplogroupReconciliation]
  def upsertByAtUri(reconciliation: HaplogroupReconciliation): Future[HaplogroupReconciliation]
  def upsertBySpecimenDonorAndDnaType(reconciliation: HaplogroupReconciliation): Future[HaplogroupReconciliation]
  def update(reconciliation: HaplogroupReconciliation): Future[Boolean]
  def softDelete(id: Int): Future[Boolean]
}
