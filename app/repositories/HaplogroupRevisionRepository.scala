package repositories

import jakarta.inject.Inject
import models.Haplogroup
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

trait HaplogroupRevisionRepository {
  def getHaplogroupAtRevision(haplogroupId: Int, revisionId: Int): Future[Option[Haplogroup]]

  def getLatestRevision(haplogroupId: Int): Future[Option[Haplogroup]]

  def getRevisionHistory(haplogroupId: Int): Future[Seq[Haplogroup]]

  def createNewRevision(haplogroup: Haplogroup): Future[Int]

  def getChildrenAtRevision(haplogroupId: Int, revisionId: Int): Future[Seq[Haplogroup]]

  def getAncestryAtRevision(haplogroupId: Int, revisionId: Int): Future[Seq[Haplogroup]]
}

class HaplogroupRevisionRepositoryImpl @Inject()(
                                                  dbConfigProvider: DatabaseConfigProvider
                                                )(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with HaplogroupRevisionRepository {

  import models.dal.DatabaseSchema.*
  import models.dal.MyPostgresProfile.api.*


  override def getHaplogroupAtRevision(haplogroupId: Int, revisionId: Int): Future[Option[Haplogroup]] = {
    val query = haplogroups
      .filter(h => h.haplogroupId === haplogroupId && h.revisionId === revisionId)
      .result.headOption

    runQuery(query)
  }

  override def getLatestRevision(haplogroupId: Int): Future[Option[Haplogroup]] = {
    val query = haplogroups
      .filter(_.haplogroupId === haplogroupId)
      .sortBy(_.revisionId.desc)
      .take(1)
      .result.headOption

    runQuery(query)
  }

  override def getRevisionHistory(haplogroupId: Int): Future[Seq[Haplogroup]] = {
    val query = haplogroups
      .filter(_.haplogroupId === haplogroupId)
      .sortBy(_.revisionId.desc)
      .result

    runQuery(query)
  }

  override def createNewRevision(haplogroup: Haplogroup): Future[Int] = {
    val nextRevisionQuery = haplogroups
      .filter(_.haplogroupId === haplogroup.id)
      .map(_.revisionId)
      .max
      .getOrElse(1)
      .result
      .map(_ + 1)

    runQuery(nextRevisionQuery.flatMap { nextRev =>
      haplogroups += haplogroup.copy(revisionId = nextRev)
    })
  }

  override def getChildrenAtRevision(haplogroupId: Int, revisionId: Int): Future[Seq[Haplogroup]] = {
    val query = for {
      rel <- haplogroupRelationships if rel.parentHaplogroupId === haplogroupId &&
        rel.revisionId === revisionId
      child <- haplogroups if child.haplogroupId === rel.childHaplogroupId &&
        child.revisionId === revisionId
    } yield child

    runQuery(query.result)
  }

  override def getAncestryAtRevision(haplogroupId: Int, revisionId: Int): Future[Seq[Haplogroup]] = {
    def recursiveAncestors(currentId: Int, ancestors: Seq[Haplogroup] = Seq.empty): DBIO[Seq[Haplogroup]] = {
      val query = for {
        rel <- haplogroupRelationships if rel.childHaplogroupId === currentId &&
          rel.revisionId === revisionId
        parent <- haplogroups if parent.haplogroupId === rel.parentHaplogroupId &&
          parent.revisionId === revisionId
      } yield parent

      query.result.flatMap { parents =>
        if (parents.isEmpty) DBIO.successful(ancestors)
        else recursiveAncestors(parents.head.id.get, ancestors ++ parents)
      }
    }

    runQuery(recursiveAncestors(haplogroupId))
  }
}