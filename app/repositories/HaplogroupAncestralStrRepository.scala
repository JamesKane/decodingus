package repositories

import jakarta.inject.Inject
import models.domain.haplogroups.HaplogroupAncestralStr
import play.api.Logging
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

trait HaplogroupAncestralStrRepository {
  def findByHaplogroup(haplogroupId: Int): Future[Seq[HaplogroupAncestralStr]]
  def findByHaplogroupAndMarker(haplogroupId: Int, markerName: String): Future[Option[HaplogroupAncestralStr]]
  def findByHaplogroupAndMarkers(haplogroupId: Int, markerNames: Seq[String]): Future[Seq[HaplogroupAncestralStr]]
  def upsert(motif: HaplogroupAncestralStr): Future[Int]
  def upsertBatch(motifs: Seq[HaplogroupAncestralStr]): Future[Seq[Int]]
  def deleteByHaplogroup(haplogroupId: Int): Future[Int]
}

class HaplogroupAncestralStrRepositoryImpl @Inject()(
  dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with HaplogroupAncestralStrRepository
    with Logging {

  import models.dal.DatabaseSchema.domain.haplogroups.haplogroupAncestralStrs
  import models.dal.MyPostgresProfile.api.*

  override def findByHaplogroup(haplogroupId: Int): Future[Seq[HaplogroupAncestralStr]] =
    runQuery(haplogroupAncestralStrs.filter(_.haplogroupId === haplogroupId).result)

  override def findByHaplogroupAndMarker(haplogroupId: Int, markerName: String): Future[Option[HaplogroupAncestralStr]] =
    runQuery(haplogroupAncestralStrs
      .filter(r => r.haplogroupId === haplogroupId && r.markerName === markerName)
      .result.headOption)

  override def findByHaplogroupAndMarkers(haplogroupId: Int, markerNames: Seq[String]): Future[Seq[HaplogroupAncestralStr]] =
    runQuery(haplogroupAncestralStrs
      .filter(r => r.haplogroupId === haplogroupId && r.markerName.inSet(markerNames))
      .result)

  override def upsert(motif: HaplogroupAncestralStr): Future[Int] =
    runQuery(haplogroupAncestralStrs.insertOrUpdate(motif))

  override def upsertBatch(motifs: Seq[HaplogroupAncestralStr]): Future[Seq[Int]] =
    Future.sequence(motifs.map(upsert))

  override def deleteByHaplogroup(haplogroupId: Int): Future[Int] =
    runQuery(haplogroupAncestralStrs.filter(_.haplogroupId === haplogroupId).delete)
}
