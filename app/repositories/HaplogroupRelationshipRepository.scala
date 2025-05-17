package repositories

import jakarta.inject.Inject
import models.{Haplogroup, HaplogroupRelationship}
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

trait HaplogroupRelationshipRepository {
  def getSubtreeRelationships(rootId: Int): Future[Seq[(Haplogroup, HaplogroupRelationship)]]
  def getCurrentValidRelationships: Future[Seq[(HaplogroupRelationship, Haplogroup)]]
  def createRelationshipRevision(relationship: HaplogroupRelationship): Future[Int]
  def getRelationshipsAtRevision(revisionId: Int): Future[Seq[(HaplogroupRelationship, Haplogroup, Haplogroup)]]
}

class HaplogroupRelationshipRepositoryImpl @Inject()(
                                                     dbConfigProvider: DatabaseConfigProvider
                                                   )(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with HaplogroupRelationshipRepository {

  import models.dal.DatabaseSchema.*
  import models.dal.MyPostgresProfile.api.*

  override def getSubtreeRelationships(rootId: Int): Future[Seq[(Haplogroup, HaplogroupRelationship)]] = {
    val query = for {
      root <- haplogroups if root.haplogroupId === rootId
      rel <- haplogroupRelationships if rel.parentHaplogroupId === root.haplogroupId
      child <- haplogroups if child.haplogroupId === rel.childHaplogroupId
    } yield (child, rel)

    runQuery(query.result)
  }

  override def getCurrentValidRelationships: Future[Seq[(HaplogroupRelationship, Haplogroup)]] = {
    val now = java.time.LocalDateTime.now()
    val query = for {
      rel <- haplogroupRelationships if
        rel.validFrom <= now &&
          (rel.validUntil.isEmpty || rel.validUntil > now)
      haplogroup <- haplogroups if haplogroup.haplogroupId === rel.childHaplogroupId
    } yield (rel, haplogroup)

    runQuery(query.result)
  }

  override def createRelationshipRevision(relationship: HaplogroupRelationship): Future[Int] = {
    val nextRevisionQuery = haplogroupRelationships
      .filter(r =>
        r.parentHaplogroupId === relationship.parentHaplogroupId &&
          r.childHaplogroupId === relationship.childHaplogroupId
      )
      .map(_.revisionId)
      .max
      .getOrElse(1)
      .result
      .map(_ + 1)

    runQuery(nextRevisionQuery.flatMap { nextRev =>
      haplogroupRelationships += relationship.copy(revisionId = nextRev)
    })
  }

  override def getRelationshipsAtRevision(revisionId: Int): Future[Seq[(HaplogroupRelationship, Haplogroup, Haplogroup)]] = {
    val query = for {
      rel <- haplogroupRelationships if rel.revisionId === revisionId
      parent <- haplogroups if parent.haplogroupId === rel.parentHaplogroupId &&
        parent.revisionId === revisionId
      child <- haplogroups if child.haplogroupId === rel.childHaplogroupId &&
        child.revisionId === revisionId
    } yield (rel, parent, child)

    runQuery(query.result)
  }
}
