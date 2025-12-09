package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.{DnaType, HaplogroupReconciliation}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class HaplogroupReconciliationRepositoryImpl @Inject()(
  protected val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends HaplogroupReconciliationRepository with HasDatabaseConfigProvider[JdbcProfile] {

  private val reconciliations = DatabaseSchema.domain.genomics.haplogroupReconciliations

  override def findById(id: Int): Future[Option[HaplogroupReconciliation]] = {
    db.run(reconciliations.filter(r => r.id === id && !r.deleted).result.headOption)
  }

  override def findByAtUri(atUri: String): Future[Option[HaplogroupReconciliation]] = {
    db.run(reconciliations.filter(r => r.atUri === atUri && !r.deleted).result.headOption)
  }

  override def findBySpecimenDonorId(specimenDonorId: Int): Future[Seq[HaplogroupReconciliation]] = {
    db.run(reconciliations.filter(r => r.specimenDonorId === specimenDonorId && !r.deleted).result)
  }

  override def findBySpecimenDonorIdAndDnaType(specimenDonorId: Int, dnaType: DnaType): Future[Option[HaplogroupReconciliation]] = {
    db.run(
      reconciliations.filter(r =>
        r.specimenDonorId === specimenDonorId &&
        r.dnaType === dnaType &&
        !r.deleted
      ).result.headOption
    )
  }

  override def findByConsensusHaplogroup(haplogroup: String): Future[Seq[HaplogroupReconciliation]] = {
    // Note: This would be more efficient with a JSONB index, but we're using the status->>'consensusHaplogroup' path
    db.run(reconciliations.filter(r => !r.deleted).result).map { results =>
      results.filter(_.status.consensusHaplogroup.contains(haplogroup))
    }
  }

  override def create(reconciliation: HaplogroupReconciliation): Future[HaplogroupReconciliation] = {
    db.run(
      (reconciliations returning reconciliations.map(_.id)
        into ((r, id) => r.copy(id = Some(id)))) += reconciliation
    )
  }

  override def upsertByAtUri(reconciliation: HaplogroupReconciliation): Future[HaplogroupReconciliation] = {
    reconciliation.atUri match {
      case None => create(reconciliation)
      case Some(uri) =>
        findByAtUri(uri).flatMap {
          case Some(existing) =>
            val updated = reconciliation.copy(
              id = existing.id,
              createdAt = existing.createdAt,
              updatedAt = LocalDateTime.now()
            )
            update(updated).map(_ => updated)
          case None => create(reconciliation)
        }
    }
  }

  override def upsertBySpecimenDonorAndDnaType(reconciliation: HaplogroupReconciliation): Future[HaplogroupReconciliation] = {
    findBySpecimenDonorIdAndDnaType(reconciliation.specimenDonorId, reconciliation.dnaType).flatMap {
      case Some(existing) =>
        val updated = reconciliation.copy(
          id = existing.id,
          createdAt = existing.createdAt,
          updatedAt = LocalDateTime.now()
        )
        update(updated).map(_ => updated)
      case None => create(reconciliation)
    }
  }

  override def update(reconciliation: HaplogroupReconciliation): Future[Boolean] = {
    reconciliation.id match {
      case None => Future.successful(false)
      case Some(id) =>
        val updated = reconciliation.copy(updatedAt = LocalDateTime.now())
        db.run(reconciliations.filter(_.id === id).update(updated)).map(_ > 0)
    }
  }

  override def softDelete(id: Int): Future[Boolean] = {
    db.run(
      reconciliations.filter(_.id === id)
        .map(r => (r.deleted, r.updatedAt))
        .update((true, LocalDateTime.now()))
    ).map(_ > 0)
  }
}
