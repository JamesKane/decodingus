package repositories

import jakarta.inject.Inject
import models.HaplogroupType
import models.domain.Haplogroup
import play.api.Logging
import play.api.db.slick.DatabaseConfigProvider
import slick.jdbc.GetResult

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

  import models.dal.domain.DatabaseSchema.haplogroupRelationships
  import models.dal.domain.DatabaseSchema.domain.haplogroups
  import models.dal.MyPostgresProfile.api.*

  override def getHaplogroupByName(name: String, haplogroupType: HaplogroupType): Future[Option[Haplogroup]] = {
    val query = haplogroups
      .filter(h => h.name === name && h.haplogroupType === haplogroupType)
      .result
      .headOption

    runQuery(query)
  }

  override def getAncestors(haplogroupId: Int): Future[Seq[Haplogroup]] = {
    implicit val getHaplogroupResult: GetResult[Haplogroup] = GetResult(r =>
      Haplogroup(
        id = r.nextIntOption(),
        name = r.nextString(),
        lineage = r.nextStringOption(),
        description = r.nextStringOption(),
        haplogroupType = HaplogroupType.fromString(r.nextString()).getOrElse(throw new IllegalArgumentException("Invalid haplogroup type")),
        revisionId = r.nextInt(),
        source = r.nextString(),
        confidenceLevel = r.nextString(),
        validFrom = r.nextTimestampOption().map(_.toLocalDateTime).orNull,
        validUntil = r.nextTimestampOption().map(_.toLocalDateTime)
      )
    )

    // Define the recursive CTE query
    val recursiveQuery = sql"""
      WITH RECURSIVE ancestor_tree AS (
        -- Base case: immediate parent
        SELECT h.*, 1 as level
        FROM haplogroup_relationship hr
        JOIN haplogroup h ON h.haplogroup_id = hr.parent_haplogroup_id
        WHERE hr.child_haplogroup_id = $haplogroupId

        UNION

        -- Recursive case: parents of parents
        SELECT h.*, at.level + 1
        FROM haplogroup_relationship hr
        JOIN haplogroup h ON h.haplogroup_id = hr.parent_haplogroup_id
        JOIN ancestor_tree at ON hr.child_haplogroup_id = at.haplogroup_id
      )
      SELECT
        haplogroup_id, name, lineage, description, haplogroup_type,
        revision_id, source, confidence_level, valid_from, valid_until
      FROM ancestor_tree
      ORDER BY level DESC
    """.as[Haplogroup]

    db.run(recursiveQuery)

  }

  override def getDirectChildren(haplogroupId: Int): Future[Seq[Haplogroup]] = {
    val query = for {
      rel <- haplogroupRelationships if rel.parentHaplogroupId === haplogroupId
      child <- haplogroups if child.haplogroupId === rel.childHaplogroupId
    } yield child

    runQuery(query.result)
  }
}
