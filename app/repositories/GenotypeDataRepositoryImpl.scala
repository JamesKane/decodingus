package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.GenotypeData
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class GenotypeDataRepositoryImpl @Inject()(
  protected val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends GenotypeDataRepository with HasDatabaseConfigProvider[JdbcProfile] {

  private val genotypeData = DatabaseSchema.domain.genomics.genotypeData

  override def findById(id: Int): Future[Option[GenotypeData]] = {
    db.run(genotypeData.filter(g => g.id === id && !g.deleted).result.headOption)
  }

  override def findByAtUri(atUri: String): Future[Option[GenotypeData]] = {
    db.run(genotypeData.filter(g => g.atUri === atUri && !g.deleted).result.headOption)
  }

  override def findBySampleGuid(sampleGuid: UUID): Future[Seq[GenotypeData]] = {
    db.run(genotypeData.filter(g => g.sampleGuid === sampleGuid && !g.deleted).result)
  }

  override def findByProvider(provider: String): Future[Seq[GenotypeData]] = {
    db.run(genotypeData.filter(g => g.provider === provider && !g.deleted).result)
  }

  override def create(data: GenotypeData): Future[GenotypeData] = {
    db.run(
      (genotypeData returning genotypeData.map(_.id)
        into ((g, id) => g.copy(id = Some(id)))) += data
    )
  }

  override def upsertByAtUri(data: GenotypeData): Future[GenotypeData] = {
    data.atUri match {
      case None => create(data)
      case Some(uri) =>
        findByAtUri(uri).flatMap {
          case Some(existing) =>
            val updated = data.copy(
              id = existing.id,
              createdAt = existing.createdAt,
              updatedAt = LocalDateTime.now()
            )
            update(updated).map(_ => updated)
          case None => create(data)
        }
    }
  }

  override def update(data: GenotypeData): Future[Boolean] = {
    data.id match {
      case None => Future.successful(false)
      case Some(id) =>
        val updated = data.copy(updatedAt = LocalDateTime.now())
        db.run(genotypeData.filter(_.id === id).update(updated)).map(_ > 0)
    }
  }

  override def softDelete(id: Int): Future[Boolean] = {
    db.run(
      genotypeData.filter(_.id === id)
        .map(g => (g.deleted, g.updatedAt))
        .update((true, LocalDateTime.now()))
    ).map(_ > 0)
  }

  override def findBySourceFileHash(hash: String): Future[Option[GenotypeData]] = {
    db.run(genotypeData.filter(g => g.sourceFileHash === hash && !g.deleted).result.headOption)
  }
}
