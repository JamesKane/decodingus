package repositories

import jakarta.inject.Inject
import models.HaplogroupType
import models.domain.haplogroups.{Haplogroup, HaplogroupProvenance}
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

  /**
   * Gets the parent haplogroup of the specified haplogroup.
   *
   * @param haplogroupId the unique identifier of the haplogroup
   * @return a Future containing an Option of the parent Haplogroup if one exists
   */
  def getParent(haplogroupId: Int): Future[Option[Haplogroup]]

  // === Curator CRUD Methods ===

  /**
   * Find a haplogroup by ID (active only - not soft-deleted).
   */
  def findById(id: Int): Future[Option[Haplogroup]]

  /**
   * Search haplogroups by name with optional type filter (active only).
   */
  def search(query: String, haplogroupType: Option[HaplogroupType], limit: Int, offset: Int): Future[Seq[Haplogroup]]

  /**
   * Count haplogroups matching search criteria (active only).
   */
  def count(query: Option[String], haplogroupType: Option[HaplogroupType]): Future[Int]

  /**
   * Count haplogroups by type (active only).
   */
  def countByType(haplogroupType: HaplogroupType): Future[Int]

  /**
   * Create a new haplogroup.
   */
  def create(haplogroup: Haplogroup): Future[Int]

  /**
   * Update an existing haplogroup.
   */
  def update(haplogroup: Haplogroup): Future[Boolean]

  /**
   * Soft-delete a haplogroup by setting valid_until to now.
   * Also reassigns all children to the deleted haplogroup's parent.
   *
   * @param id the haplogroup ID to soft-delete
   * @param source the source attribution for the relationship changes
   * @return true if successful, false if haplogroup not found
   */
  def softDelete(id: Int, source: String): Future[Boolean]

  // === Tree Restructuring Methods ===

  /**
   * Update a haplogroup's parent by soft-deleting the old relationship and creating a new one.
   *
   * @param childId the ID of the child haplogroup to re-parent
   * @param newParentId the ID of the new parent haplogroup
   * @param source the source attribution for the relationship change
   * @return true if successful
   */
  def updateParent(childId: Int, newParentId: Int, source: String): Future[Boolean]

  /**
   * Create a new haplogroup with an optional parent relationship.
   *
   * @param haplogroup the haplogroup to create
   * @param parentId optional parent haplogroup ID
   * @param source the source attribution for the relationship
   * @return the ID of the newly created haplogroup
   */
  def createWithParent(haplogroup: Haplogroup, parentId: Option[Int], source: String): Future[Int]

  /**
   * Find root haplogroups (those with no parent) for a given type.
   *
   * @param haplogroupType the type of haplogroup (Y or MT)
   * @return a sequence of root haplogroups for that type
   */
  def findRoots(haplogroupType: HaplogroupType): Future[Seq[Haplogroup]]

  // === Tree Merge Methods ===

  /**
   * Update the provenance field for a haplogroup.
   *
   * @param id the haplogroup ID
   * @param provenance the new provenance data
   * @return true if updated successfully
   */
  def updateProvenance(id: Int, provenance: HaplogroupProvenance): Future[Boolean]

  /**
   * Get all haplogroups of a type with their associated variant names.
   * Used for building variant-based lookup index for merge operations.
   *
   * @param haplogroupType the type of haplogroup (Y or MT)
   * @return sequence of tuples: (haplogroup, list of variant names)
   */
  def getAllWithVariantNames(haplogroupType: HaplogroupType): Future[Seq[(Haplogroup, Seq[String])]]
}

class HaplogroupCoreRepositoryImpl @Inject()(
                                              dbConfigProvider: DatabaseConfigProvider
                                            )(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with HaplogroupCoreRepository
    with Logging {

  import models.dal.DatabaseSchema.domain.haplogroups.{haplogroupRelationships, haplogroups}
  import models.dal.MyPostgresProfile.api.*

  import java.time.LocalDateTime

  /** Filter for active (non-soft-deleted) haplogroups */
  private def activeHaplogroups = haplogroups.filter(h =>
    h.validUntil.isEmpty || h.validUntil > LocalDateTime.now()
  )

  /** Filter for active relationships */
  private def activeRelationships = haplogroupRelationships.filter(r =>
    r.validUntil.isEmpty || r.validUntil > LocalDateTime.now()
  )

  override def getHaplogroupByName(name: String, haplogroupType: HaplogroupType): Future[Option[Haplogroup]] = {
    val query = activeHaplogroups
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
        FROM tree.haplogroup_relationship hr
        JOIN tree.haplogroup h ON h.haplogroup_id = hr.parent_haplogroup_id
        WHERE hr.child_haplogroup_id = $haplogroupId

        UNION

        -- Recursive case: parents of parents
        SELECT h.*, at.level + 1
        FROM tree.haplogroup_relationship hr
        JOIN tree.haplogroup h ON h.haplogroup_id = hr.parent_haplogroup_id
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
      rel <- activeRelationships if rel.parentHaplogroupId === haplogroupId
      child <- activeHaplogroups if child.haplogroupId === rel.childHaplogroupId
    } yield child

    runQuery(query.result)
  }

  override def getParent(haplogroupId: Int): Future[Option[Haplogroup]] = {
    val query = for {
      rel <- activeRelationships if rel.childHaplogroupId === haplogroupId
      parent <- activeHaplogroups if parent.haplogroupId === rel.parentHaplogroupId
    } yield parent

    runQuery(query.result.headOption)
  }

  // === Curator CRUD Methods Implementation ===

  override def findById(id: Int): Future[Option[Haplogroup]] = {
    runQuery(activeHaplogroups.filter(_.haplogroupId === id).result.headOption)
  }

  override def search(query: String, haplogroupType: Option[HaplogroupType], limit: Int, offset: Int): Future[Seq[Haplogroup]] = {
    val baseQuery = activeHaplogroups
      .filter(h => h.name.toUpperCase.like(s"%${query.toUpperCase}%"))

    val filteredQuery = haplogroupType match {
      case Some(hgType) => baseQuery.filter(_.haplogroupType === hgType)
      case None => baseQuery
    }

    runQuery(
      filteredQuery
        .sortBy(_.name)
        .drop(offset)
        .take(limit)
        .result
    )
  }

  override def count(query: Option[String], haplogroupType: Option[HaplogroupType]): Future[Int] = {
    val baseQuery = query match {
      case Some(q) => activeHaplogroups.filter(h => h.name.toUpperCase.like(s"%${q.toUpperCase}%"))
      case None => activeHaplogroups
    }

    val filteredQuery = haplogroupType match {
      case Some(hgType) => baseQuery.filter(_.haplogroupType === hgType)
      case None => baseQuery
    }

    runQuery(filteredQuery.length.result)
  }

  override def countByType(haplogroupType: HaplogroupType): Future[Int] = {
    runQuery(activeHaplogroups.filter(_.haplogroupType === haplogroupType).length.result)
  }

  override def create(haplogroup: Haplogroup): Future[Int] = {
    runQuery(
      (haplogroups returning haplogroups.map(_.haplogroupId)) += haplogroup
    )
  }

  override def update(haplogroup: Haplogroup): Future[Boolean] = {
    haplogroup.id match {
      case Some(id) =>
        runQuery(
          haplogroups
            .filter(_.haplogroupId === id)
            .map(h => (h.name, h.lineage, h.description, h.source, h.confidenceLevel))
            .update((haplogroup.name, haplogroup.lineage, haplogroup.description, haplogroup.source, haplogroup.confidenceLevel))
        ).map(_ > 0)
      case None => Future.successful(false)
    }
  }

  override def softDelete(id: Int, source: String): Future[Boolean] = {
    val now = LocalDateTime.now()

    // Step 1: Find the haplogroup's current parent relationship
    val findParentAction = activeRelationships
      .filter(_.childHaplogroupId === id)
      .map(_.parentHaplogroupId)
      .result
      .headOption

    // Step 2: Find all children of this haplogroup
    val findChildrenAction = activeRelationships
      .filter(_.parentHaplogroupId === id)
      .result

    val softDeleteAction = for {
      maybeParentId <- findParentAction
      childRelationships <- findChildrenAction

      // Step 3: Soft-delete the haplogroup by setting valid_until
      updated <- haplogroups
        .filter(_.haplogroupId === id)
        .filter(h => h.validUntil.isEmpty || h.validUntil > now)
        .map(_.validUntil)
        .update(Some(now))

      // Step 4: Soft-delete the haplogroup's parent relationship
      _ <- haplogroupRelationships
        .filter(_.childHaplogroupId === id)
        .filter(r => r.validUntil.isEmpty || r.validUntil > now)
        .map(_.validUntil)
        .update(Some(now))

      // Step 5: If there's a parent, reassign children to it
      _ <- maybeParentId match {
        case Some(parentId) =>
          // End current relationships for children
          val endCurrentRelationships = haplogroupRelationships
            .filter(r => r.parentHaplogroupId === id && (r.validUntil.isEmpty || r.validUntil > now))
            .map(_.validUntil)
            .update(Some(now))

          // Create new relationships pointing to the grandparent
          import models.domain.haplogroups.HaplogroupRelationship
          val newRelationships = childRelationships.map { childRel =>
            HaplogroupRelationship(
              id = None,
              childHaplogroupId = childRel.childHaplogroupId,
              parentHaplogroupId = parentId,
              revisionId = childRel.revisionId,
              validFrom = now,
              validUntil = None,
              source = source
            )
          }
          endCurrentRelationships.andThen(
            (haplogroupRelationships ++= newRelationships).map(_ => ())
          )

        case None =>
          // No parent - just end the children's current relationships (they become roots)
          haplogroupRelationships
            .filter(r => r.parentHaplogroupId === id && (r.validUntil.isEmpty || r.validUntil > now))
            .map(_.validUntil)
            .update(Some(now))
            .map(_ => ())
      }
    } yield updated > 0

    runTransactionally(softDeleteAction)
  }

  // === Tree Restructuring Methods Implementation ===

  override def updateParent(childId: Int, newParentId: Int, source: String): Future[Boolean] = {
    import models.domain.haplogroups.HaplogroupRelationship
    val now = LocalDateTime.now()

    val updateAction = for {
      // Soft-delete the existing parent relationship
      _ <- haplogroupRelationships
        .filter(r => r.childHaplogroupId === childId && (r.validUntil.isEmpty || r.validUntil > now))
        .map(_.validUntil)
        .update(Some(now))

      // Create new relationship to new parent
      _ <- haplogroupRelationships += HaplogroupRelationship(
        id = None,
        childHaplogroupId = childId,
        parentHaplogroupId = newParentId,
        revisionId = 1,
        validFrom = now,
        validUntil = None,
        source = source
      )
    } yield true

    runTransactionally(updateAction)
  }

  override def createWithParent(haplogroup: Haplogroup, parentId: Option[Int], source: String): Future[Int] = {
    import models.domain.haplogroups.HaplogroupRelationship
    val now = LocalDateTime.now()

    val createAction = for {
      // Create the haplogroup
      newId <- (haplogroups returning haplogroups.map(_.haplogroupId)) += haplogroup

      // Create parent relationship if parentId provided
      _ <- parentId match {
        case Some(pid) =>
          haplogroupRelationships += HaplogroupRelationship(
            id = None,
            childHaplogroupId = newId,
            parentHaplogroupId = pid,
            revisionId = 1,
            validFrom = now,
            validUntil = None,
            source = source
          )
        case None =>
          DBIO.successful(())
      }
    } yield newId

    runTransactionally(createAction)
  }

  override def findRoots(haplogroupType: HaplogroupType): Future[Seq[Haplogroup]] = {
    // Find haplogroups of the given type that have no active parent relationship
    val query = activeHaplogroups
      .filter(_.haplogroupType === haplogroupType)
      .filterNot(h =>
        activeRelationships.filter(_.childHaplogroupId === h.haplogroupId).exists
      )
      .sortBy(_.name)
      .result

    runQuery(query)
  }

  // === Tree Merge Methods Implementation ===

  override def updateProvenance(id: Int, provenance: HaplogroupProvenance): Future[Boolean] = {
    runQuery(
      haplogroups
        .filter(_.haplogroupId === id)
        .map(_.provenance)
        .update(Some(provenance))
    ).map(_ > 0)
  }

  override def getAllWithVariantNames(haplogroupType: HaplogroupType): Future[Seq[(Haplogroup, Seq[String])]] = {
    import models.dal.DatabaseSchema.domain.haplogroups.haplogroupVariants
    import models.dal.DatabaseSchema.domain.genomics.variantsV2

    // Query haplogroups with their associated variant names via join
    val query = for {
      hg <- activeHaplogroups.filter(_.haplogroupType === haplogroupType)
    } yield hg

    runQuery(query.result).flatMap { hgList =>
      // For each haplogroup, fetch its variant names (using canonicalName from VariantV2 table)
      val futures = hgList.map { hg =>
        val variantQuery = for {
          hv <- haplogroupVariants.filter(_.haplogroupId === hg.id.get)
          v <- variantsV2.filter(_.variantId === hv.variantId)
        } yield v.canonicalName

        runQuery(variantQuery.result).map { variantNames =>
          (hg, variantNames.flatten) // Filter out None values
        }
      }
      Future.sequence(futures)
    }
  }
}
