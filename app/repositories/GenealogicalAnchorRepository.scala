package repositories

import jakarta.inject.Inject
import models.domain.haplogroups.{AnchorType, GenealogicalAnchor}
import play.api.Logging
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

trait GenealogicalAnchorRepository {
  def findById(id: Int): Future[Option[GenealogicalAnchor]]
  def findByHaplogroup(haplogroupId: Int): Future[Seq[GenealogicalAnchor]]
  def findByType(anchorType: AnchorType): Future[Seq[GenealogicalAnchor]]
  def create(anchor: GenealogicalAnchor): Future[GenealogicalAnchor]
  def update(anchor: GenealogicalAnchor): Future[Boolean]
  def delete(id: Int): Future[Boolean]
}

class GenealogicalAnchorRepositoryImpl @Inject()(
  dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with GenealogicalAnchorRepository
    with Logging {

  import models.dal.DatabaseSchema.domain.haplogroups.genealogicalAnchors
  import models.dal.MyPostgresProfile.api.*

  override def findById(id: Int): Future[Option[GenealogicalAnchor]] =
    runQuery(genealogicalAnchors.filter(_.id === id).result.headOption)

  override def findByHaplogroup(haplogroupId: Int): Future[Seq[GenealogicalAnchor]] =
    runQuery(genealogicalAnchors.filter(_.haplogroupId === haplogroupId).result)

  implicit private val anchorTypeMapper: BaseColumnType[AnchorType] =
    MappedColumnType.base[AnchorType, String](_.dbValue, AnchorType.fromString)

  override def findByType(anchorType: AnchorType): Future[Seq[GenealogicalAnchor]] =
    runQuery(genealogicalAnchors.filter(_.anchorType === anchorType).result)

  override def create(anchor: GenealogicalAnchor): Future[GenealogicalAnchor] =
    runQuery(
      (genealogicalAnchors returning genealogicalAnchors.map(_.id)
        into ((a, id) => a.copy(id = Some(id)))) += anchor
    )

  override def update(anchor: GenealogicalAnchor): Future[Boolean] =
    anchor.id match {
      case Some(id) =>
        runQuery(genealogicalAnchors.filter(_.id === id).update(anchor).map(_ > 0))
      case None => Future.successful(false)
    }

  override def delete(id: Int): Future[Boolean] =
    runQuery(genealogicalAnchors.filter(_.id === id).delete.map(_ > 0))
}
