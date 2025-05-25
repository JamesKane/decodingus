package repositories

import jakarta.inject.Inject
import models.domain.{Haplogroup, HaplogroupRelationship}
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository for managing haplogroup relationships and their revisions.
 */
trait HaplogroupRelationshipRepository {
  /**
   * Retrieves haplogroup subtree relationships starting from the specified root haplogroup ID.
   *
   * @param rootId The ID of the haplogroup to use as the root for fetching its subtree relationships.
   * @return A Future containing a sequence of tuples. Each tuple consists of a haplogroup and its associated relationship.
   */
  def getSubtreeRelationships(rootId: Int): Future[Seq[(Haplogroup, HaplogroupRelationship)]]

  /**
   * Retrieves the currently valid haplogroup relationships.
   *
   * This method returns a sequence of tuples, where each tuple consists of a `HaplogroupRelationship`
   * and its associated `Haplogroup`. Only relationships that are currently valid, based on their
   * validity period (`validFrom` and `validUntil`), are included in the results.
   *
   * @return A Future containing a sequence of tuples with a currently valid `HaplogroupRelationship`
   *         and its corresponding `Haplogroup`.
   */
  def getCurrentValidRelationships: Future[Seq[(HaplogroupRelationship, Haplogroup)]]

  /**
   * Creates a new revision for the provided haplogroup relationship.
   *
   * The revision allows tracking changes to the relationships over time by associating 
   * the relationship with a revision ID and setting validity periods.
   *
   * @param relationship The HaplogroupRelationship object representing the relationship 
   *                     to be added as a new revision.
   * @return A Future containing the ID of the newly created revision.
   */
  def createRelationshipRevision(relationship: HaplogroupRelationship): Future[Int]

  /**
   * Retrieves haplogroup relationships for a specific revision.
   *
   * This method returns a sequence of tuples representing the relationships at a particular revision.
   * Each tuple consists of a `HaplogroupRelationship`, the child `Haplogroup`, and the parent `Haplogroup`.
   *
   * @param revisionId The ID of the revision for which relationships are to be retrieved.
   * @return A `Future` containing a sequence of tuples, where each tuple includes a `HaplogroupRelationship`,
   *         the corresponding child `Haplogroup`, and the parent `Haplogroup`.
   */
  def getRelationshipsAtRevision(revisionId: Int): Future[Seq[(HaplogroupRelationship, Haplogroup, Haplogroup)]]
}

class HaplogroupRelationshipRepositoryImpl @Inject()(
                                                      dbConfigProvider: DatabaseConfigProvider
                                                    )(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with HaplogroupRelationshipRepository {

  import models.dal.DatabaseSchema.*
  import models.dal.DatabaseSchema.domain.haplogroupRelationships
  import models.dal.DatabaseSchema.domain.haplogroups
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
      (haplogroupRelationships returning haplogroupRelationships.map(_.haplogroupRelationshipId)) +=
        relationship.copy(revisionId = nextRev)
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
