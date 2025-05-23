package repositories

import jakarta.inject.Inject
import models.{Haplogroup, HaplogroupType}
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository interface for accessing and managing haplogroup data.
 */
trait HaplogroupCoreRepository {
  /**
   * Retrieves a haplogroup by its name and type.
   *
   * @param name           the name of the haplogroup to retrieve
   * @param haplogroupType the type of haplogroup (e.g., Y or MT)
   * @return a Future containing an Option wrapping the Haplogroup object if found, or None otherwise
   */
  def getHaplogroupByName(name: String, haplogroupType: HaplogroupType): Future[Option[Haplogroup]]

  /**
   * Retrieves the ancestor haplogroups of the specified haplogroup.
   *
   * @param haplogroupId the unique identifier of the haplogroup for which ancestors are to be retrieved
   * @return a Future containing a sequence of ancestor Haplogroup objects
   */
  def getAncestors(haplogroupId: Int): Future[Seq[Haplogroup]]

  /**
   * Retrieves the direct children of a given haplogroup by its unique identifier.
   *
   * @param haplogroupId the unique identifier of the haplogroup whose direct children are to be retrieved
   * @return a Future containing a sequence of Haplogroup objects representing the direct children
   */
  def getDirectChildren(haplogroupId: Int): Future[Seq[Haplogroup]]
}

class HaplogroupCoreRepositoryImpl @Inject()(
                                              dbConfigProvider: DatabaseConfigProvider
                                            )(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with HaplogroupCoreRepository {

  import models.dal.DatabaseSchema.{haplogroupRelationships, haplogroups}
  import models.dal.MyPostgresProfile.api.*

  override def getHaplogroupByName(name: String, haplogroupType: HaplogroupType): Future[Option[Haplogroup]] = {
    val query = haplogroups
      .filter(h => h.name === name && h.haplogroupType === haplogroupType)
      .result
      .headOption

    runQuery(query)
  }

  override def getAncestors(haplogroupId: Int): Future[Seq[Haplogroup]] = {
    val recursiveQuery = for {
      rel <- haplogroupRelationships if rel.childHaplogroupId === haplogroupId
      parent <- haplogroups if parent.haplogroupId === rel.parentHaplogroupId
    } yield parent

    runQuery(recursiveQuery.result)
  }

  override def getDirectChildren(haplogroupId: Int): Future[Seq[Haplogroup]] = {
    val query = for {
      rel <- haplogroupRelationships if rel.parentHaplogroupId === haplogroupId
      child <- haplogroups if child.haplogroupId === rel.childHaplogroupId
    } yield child

    runQuery(query.result)
  }
}
