package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.domain.genomics.{Biosample, OriginalHaplogroupEntry}
import play.api.db.slick.DatabaseConfigProvider
import play.api.libs.json.Json

import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository for managing original haplogroup data embedded as JSONB on biosample.
 */
trait BiosampleOriginalHaplogroupRepository {
  def findByBiosampleId(biosampleId: Int): Future[Seq[OriginalHaplogroupEntry]]
  def findByBiosampleAndPublication(biosampleId: Int, publicationId: Int): Future[Option[OriginalHaplogroupEntry]]
  def upsert(biosampleId: Int, entry: OriginalHaplogroupEntry): Future[Boolean]
  def delete(biosampleId: Int, publicationId: Int): Future[Boolean]
  def deleteAllByBiosampleId(biosampleId: Int): Future[Boolean]
}

@Singleton
class BiosampleOriginalHaplogroupRepositoryImpl @Inject()(
                                                           override protected val dbConfigProvider: DatabaseConfigProvider
                                                         )(implicit override protected val ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with BiosampleOriginalHaplogroupRepository {

  import models.dal.MyPostgresProfile.api.*

  private val biosamples = DatabaseSchema.domain.genomics.biosamples

  override def findByBiosampleId(biosampleId: Int): Future[Seq[OriginalHaplogroupEntry]] = {
    db.run(biosamples.filter(_.id === biosampleId).map(_.originalHaplogroups).result.headOption).map {
      case Some(Some(json)) => json.asOpt[Seq[OriginalHaplogroupEntry]].getOrElse(Seq.empty)
      case _ => Seq.empty
    }
  }

  override def findByBiosampleAndPublication(biosampleId: Int, publicationId: Int): Future[Option[OriginalHaplogroupEntry]] = {
    findByBiosampleId(biosampleId).map(_.find(_.publicationId == publicationId))
  }

  override def upsert(biosampleId: Int, entry: OriginalHaplogroupEntry): Future[Boolean] = {
    findByBiosampleId(biosampleId).flatMap { existing =>
      val updated = existing.filterNot(_.publicationId == entry.publicationId) :+ entry
      db.run(
        biosamples.filter(_.id === biosampleId)
          .map(_.originalHaplogroups)
          .update(Some(Json.toJson(updated)))
      ).map(_ > 0)
    }
  }

  override def delete(biosampleId: Int, publicationId: Int): Future[Boolean] = {
    findByBiosampleId(biosampleId).flatMap { existing =>
      val updated = existing.filterNot(_.publicationId == publicationId)
      db.run(
        biosamples.filter(_.id === biosampleId)
          .map(_.originalHaplogroups)
          .update(Some(Json.toJson(updated)))
      ).map(_ > 0)
    }
  }

  override def deleteAllByBiosampleId(biosampleId: Int): Future[Boolean] = {
    db.run(
      biosamples.filter(_.id === biosampleId)
        .map(_.originalHaplogroups)
        .update(Some(Json.toJson(Seq.empty[OriginalHaplogroupEntry])))
    ).map(_ > 0)
  }
}
