package repositories

import jakarta.inject.Inject
import models.{Haplogroup, HaplogroupType}
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository interface for managing and querying haplogroup data and revisions.
 */
trait HaplogroupRevisionRepository {
  /**
   * Retrieves the haplogroup information associated with a specific revision.
   *
   * @param haplogroupId The unique identifier of the haplogroup.
   * @param revisionId   The unique identifier of the revision.
   * @return A Future containing an Option of the Haplogroup if the specified revision exists, or None otherwise.
   */
  def getHaplogroupAtRevision(haplogroupId: Int, revisionId: Int): Future[Option[Haplogroup]]

  /**
   * Retrieves the latest revision of the specified haplogroup.
   *
   * @param haplogroupId The unique identifier of the haplogroup for which the latest revision is to be fetched.
   * @return A Future containing an Option of Haplogroup. It will be Some(Haplogroup) if a revision exists for the provided haplogroupId, otherwise None.
   */
  def getLatestRevision(haplogroupId: Int): Future[Option[Haplogroup]]

  /**
   * Retrieves the complete revision history of a haplogroup identified by its unique identifier.
   *
   * @param haplogroupId The unique identifier of the haplogroup whose revision history is to be fetched.
   * @return A Future containing a sequence of Haplogroup instances representing the revision history.
   */
  def getRevisionHistory(haplogroupId: Int): Future[Seq[Haplogroup]]

  /**
   * Creates a new revision for the specified haplogroup. The provided haplogroup must have all necessary
   * information such as its type, source, and validity periods. This method generates a new revision entry
   * and persists it in the database, returning the unique identifier of the newly created revision.
   *
   * @param haplogroup The haplogroup entity containing information required to create a new revision.
   * @return A Future containing the unique identifier of the newly created revision.
   */
  def createNewRevision(haplogroup: Haplogroup): Future[Int]

  /**
   * Retrieves the child haplogroups at a specific revision.
   *
   * @param haplogroupId The unique identifier of the parent haplogroup.
   * @param revisionId   The unique identifier of the revision to fetch children for.
   * @return A Future containing a sequence of child Haplogroup instances at the specified revision.
   */
  def getChildrenAtRevision(haplogroupId: Int, revisionId: Int): Future[Seq[Haplogroup]]

  /**
   * Retrieves the ancestry of a haplogroup at a specific revision.
   * This includes all ancestor haplogroups leading up to the specified revision.
   *
   * @param haplogroupId The unique identifier of the haplogroup whose ancestry is to be fetched.
   * @param revisionId   The unique identifier of the revision for which ancestry is to be retrieved.
   * @return A Future containing a sequence of Haplogroup instances representing the ancestry at the specified revision.
   */
  def getAncestryAtRevision(haplogroupId: Int, revisionId: Int): Future[Seq[Haplogroup]]

  /**
   * Counts the number of haplogroups of a specific type.
   *
   * @param haplogroupType The type of haplogroup to be counted (e.g., paternal or maternal lineage).
   * @return A Future containing the count of haplogroups of the specified type as an integer.
   */
  def countByType(haplogroupType: HaplogroupType): Future[Int]
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

  override def countByType(haplogroupType: HaplogroupType): Future[Int] = {
    val query = haplogroups
      .filter(_.haplogroupType === haplogroupType.toString)
      .length
      .result

    runQuery(query)
  }
}