package repositories

import jakarta.inject.Inject
import models.HaplogroupType
import models.dal.domain.haplogroups.*
import play.api.Logging
import play.api.db.slick.DatabaseConfigProvider

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository interface for WIP (Work In Progress) shadow tables.
 *
 * These tables stage merge changes before they are applied to production.
 * All operations are scoped by change_set_id for easy cleanup.
 */
trait WipTreeRepository {

  // ============================================================================
  // WIP Haplogroup Operations
  // ============================================================================

  /**
   * Create a new WIP haplogroup (staged node not yet in production).
   * Returns the generated wip_haplogroup_id.
   */
  def createWipHaplogroup(row: WipHaplogroupRow): Future[Int]

  /**
   * Bulk insert WIP haplogroups.
   */
  def createWipHaplogroups(rows: Seq[WipHaplogroupRow]): Future[Seq[Int]]

  /**
   * Get a WIP haplogroup by change set and placeholder ID.
   */
  def getWipHaplogroup(changeSetId: Int, placeholderId: Int): Future[Option[WipHaplogroupRow]]

  /**
   * Get all WIP haplogroups for a change set.
   */
  def getWipHaplogroupsForChangeSet(changeSetId: Int): Future[Seq[WipHaplogroupRow]]

  /**
   * Get WIP haplogroups by name within a change set.
   */
  def getWipHaplogroupByName(changeSetId: Int, name: String): Future[Option[WipHaplogroupRow]]

  // ============================================================================
  // WIP Relationship Operations
  // ============================================================================

  /**
   * Create a new WIP relationship (staged parent-child relationship).
   */
  def createWipRelationship(row: WipRelationshipRow): Future[Int]

  /**
   * Bulk insert WIP relationships.
   */
  def createWipRelationships(rows: Seq[WipRelationshipRow]): Future[Seq[Int]]

  /**
   * Get all WIP relationships for a change set.
   */
  def getWipRelationshipsForChangeSet(changeSetId: Int): Future[Seq[WipRelationshipRow]]

  /**
   * Get relationships where a specific placeholder ID is the child.
   */
  def getWipRelationshipsForChild(changeSetId: Int, childPlaceholderId: Int): Future[Seq[WipRelationshipRow]]

  /**
   * Get relationships where a specific placeholder ID is the parent.
   */
  def getWipRelationshipsForParent(changeSetId: Int, parentPlaceholderId: Int): Future[Seq[WipRelationshipRow]]

  // ============================================================================
  // WIP Variant Operations
  // ============================================================================

  /**
   * Create a new WIP variant association.
   */
  def createWipHaplogroupVariant(row: WipHaplogroupVariantRow): Future[Int]

  /**
   * Bulk insert WIP variant associations.
   */
  def createWipHaplogroupVariants(rows: Seq[WipHaplogroupVariantRow]): Future[Seq[Int]]

  /**
   * Bulk insert WIP variant associations, ignoring duplicates.
   * Filters out any variants that already exist for the same haplogroup/placeholder.
   */
  def upsertWipHaplogroupVariants(rows: Seq[WipHaplogroupVariantRow]): Future[Seq[Int]]

  /**
   * Get all WIP variant associations for a change set.
   */
  def getWipVariantsForChangeSet(changeSetId: Int): Future[Seq[WipHaplogroupVariantRow]]

  /**
   * Get variants for a specific placeholder haplogroup.
   */
  def getWipVariantsForPlaceholder(changeSetId: Int, placeholderId: Int): Future[Seq[WipHaplogroupVariantRow]]

  /**
   * Get variants for a specific production haplogroup.
   */
  def getWipVariantsForHaplogroup(changeSetId: Int, haplogroupId: Int): Future[Seq[WipHaplogroupVariantRow]]

  // ============================================================================
  // WIP Reparent Operations
  // ============================================================================

  /**
   * Create a new WIP reparent operation.
   */
  def createWipReparent(row: WipReparentRow): Future[Int]

  /**
   * Create or update a WIP reparent operation.
   * If a reparent already exists for this haplogroup in this change set, update it.
   * This handles cases where a node is reparented multiple times during a merge
   * (e.g., once by SUBTREE_LOOK_AHEAD and again by DEPTH_GRAFT).
   */
  def upsertWipReparent(row: WipReparentRow): Future[Int]

  /**
   * Bulk insert WIP reparent operations.
   */
  def createWipReparents(rows: Seq[WipReparentRow]): Future[Seq[Int]]

  /**
   * Get all WIP reparent operations for a change set.
   */
  def getWipReparentsForChangeSet(changeSetId: Int): Future[Seq[WipReparentRow]]

  /**
   * Get reparent for a specific haplogroup.
   */
  def getWipReparent(changeSetId: Int, haplogroupId: Int): Future[Option[WipReparentRow]]

  // ============================================================================
  // Cleanup Operations
  // ============================================================================

  /**
   * Delete all WIP data for a change set.
   * Called when discarding a change set.
   */
  def deleteWipDataForChangeSet(changeSetId: Int): Future[Int]

  // ============================================================================
  // Statistics
  // ============================================================================

  /**
   * Get counts of WIP data for a change set.
   */
  def getWipStatistics(changeSetId: Int): Future[WipStatistics]
}

/**
 * Statistics about WIP data for a change set.
 */
case class WipStatistics(
  haplogroups: Int,
  relationships: Int,
  variants: Int,
  reparents: Int
)

class WipTreeRepositoryImpl @Inject()(
  dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with WipTreeRepository
    with Logging {

  import models.dal.DatabaseSchema.domain.haplogroups.{wipHaplogroups, wipRelationships, wipHaplogroupVariants, wipReparents}
  import models.dal.MyPostgresProfile.api.*

  // ============================================================================
  // WIP Haplogroup Implementations
  // ============================================================================

  override def createWipHaplogroup(row: WipHaplogroupRow): Future[Int] = {
    val query = (wipHaplogroups returning wipHaplogroups.map(_.id)) += row
    runQuery(query)
  }

  override def createWipHaplogroups(rows: Seq[WipHaplogroupRow]): Future[Seq[Int]] = {
    val query = (wipHaplogroups returning wipHaplogroups.map(_.id)) ++= rows
    runQuery(query)
  }

  override def getWipHaplogroup(changeSetId: Int, placeholderId: Int): Future[Option[WipHaplogroupRow]] = {
    val query = wipHaplogroups
      .filter(h => h.changeSetId === changeSetId && h.placeholderId === placeholderId)
      .result.headOption
    runQuery(query)
  }

  override def getWipHaplogroupsForChangeSet(changeSetId: Int): Future[Seq[WipHaplogroupRow]] = {
    val query = wipHaplogroups
      .filter(_.changeSetId === changeSetId)
      .sortBy(_.placeholderId)
      .result
    runQuery(query)
  }

  override def getWipHaplogroupByName(changeSetId: Int, name: String): Future[Option[WipHaplogroupRow]] = {
    val query = wipHaplogroups
      .filter(h => h.changeSetId === changeSetId && h.name === name)
      .result.headOption
    runQuery(query)
  }

  // ============================================================================
  // WIP Relationship Implementations
  // ============================================================================

  override def createWipRelationship(row: WipRelationshipRow): Future[Int] = {
    val query = (wipRelationships returning wipRelationships.map(_.id)) += row
    runQuery(query)
  }

  override def createWipRelationships(rows: Seq[WipRelationshipRow]): Future[Seq[Int]] = {
    val query = (wipRelationships returning wipRelationships.map(_.id)) ++= rows
    runQuery(query)
  }

  override def getWipRelationshipsForChangeSet(changeSetId: Int): Future[Seq[WipRelationshipRow]] = {
    val query = wipRelationships
      .filter(_.changeSetId === changeSetId)
      .result
    runQuery(query)
  }

  override def getWipRelationshipsForChild(changeSetId: Int, childPlaceholderId: Int): Future[Seq[WipRelationshipRow]] = {
    val query = wipRelationships
      .filter(r => r.changeSetId === changeSetId && r.childPlaceholderId === childPlaceholderId)
      .result
    runQuery(query)
  }

  override def getWipRelationshipsForParent(changeSetId: Int, parentPlaceholderId: Int): Future[Seq[WipRelationshipRow]] = {
    val query = wipRelationships
      .filter(r => r.changeSetId === changeSetId && r.parentPlaceholderId === parentPlaceholderId)
      .result
    runQuery(query)
  }

  // ============================================================================
  // WIP Variant Implementations
  // ============================================================================

  override def createWipHaplogroupVariant(row: WipHaplogroupVariantRow): Future[Int] = {
    val query = (wipHaplogroupVariants returning wipHaplogroupVariants.map(_.id)) += row
    runQuery(query)
  }

  override def createWipHaplogroupVariants(rows: Seq[WipHaplogroupVariantRow]): Future[Seq[Int]] = {
    if (rows.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      val query = (wipHaplogroupVariants returning wipHaplogroupVariants.map(_.id)) ++= rows
      runQuery(query)
    }
  }

  override def upsertWipHaplogroupVariants(rows: Seq[WipHaplogroupVariantRow]): Future[Seq[Int]] = {
    if (rows.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      // Get the change set ID (all rows should have the same one)
      val changeSetId = rows.head.changeSetId

      // Get existing variants for this change set, then filter out duplicates
      getWipVariantsForChangeSet(changeSetId).flatMap { existing =>
        // Build a set of existing keys: (haplogroupId, placeholderId, variantId)
        val existingKeys = existing.map { v =>
          (v.haplogroupId, v.haplogroupPlaceholderId, v.variantId)
        }.toSet

        // Filter out rows that would be duplicates
        val newRows = rows.filterNot { row =>
          existingKeys.contains((row.haplogroupId, row.haplogroupPlaceholderId, row.variantId))
        }

        if (newRows.isEmpty) {
          Future.successful(Seq.empty)
        } else {
          val query = (wipHaplogroupVariants returning wipHaplogroupVariants.map(_.id)) ++= newRows
          runQuery(query)
        }
      }
    }
  }

  override def getWipVariantsForChangeSet(changeSetId: Int): Future[Seq[WipHaplogroupVariantRow]] = {
    val query = wipHaplogroupVariants
      .filter(_.changeSetId === changeSetId)
      .result
    runQuery(query)
  }

  override def getWipVariantsForPlaceholder(changeSetId: Int, placeholderId: Int): Future[Seq[WipHaplogroupVariantRow]] = {
    val query = wipHaplogroupVariants
      .filter(v => v.changeSetId === changeSetId && v.haplogroupPlaceholderId === placeholderId)
      .result
    runQuery(query)
  }

  override def getWipVariantsForHaplogroup(changeSetId: Int, haplogroupId: Int): Future[Seq[WipHaplogroupVariantRow]] = {
    val query = wipHaplogroupVariants
      .filter(v => v.changeSetId === changeSetId && v.haplogroupId === haplogroupId)
      .result
    runQuery(query)
  }

  // ============================================================================
  // WIP Reparent Implementations
  // ============================================================================

  override def createWipReparent(row: WipReparentRow): Future[Int] = {
    val query = (wipReparents returning wipReparents.map(_.id)) += row
    runQuery(query)
  }

  override def upsertWipReparent(row: WipReparentRow): Future[Int] = {
    // Check if a reparent already exists for this haplogroup in this change set
    getWipReparent(row.changeSetId, row.haplogroupId).flatMap {
      case Some(existing) =>
        // Update the existing reparent with the new parent
        val updateQuery = wipReparents
          .filter(r => r.changeSetId === row.changeSetId && r.haplogroupId === row.haplogroupId)
          .map(r => (r.newParentId, r.newParentPlaceholderId, r.source, r.createdAt))
          .update((row.newParentId, row.newParentPlaceholderId, row.source, row.createdAt))
        runQuery(updateQuery).map(_ => existing.id.getOrElse(0))
      case None =>
        // No existing reparent, create a new one
        createWipReparent(row)
    }
  }

  override def createWipReparents(rows: Seq[WipReparentRow]): Future[Seq[Int]] = {
    if (rows.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      val query = (wipReparents returning wipReparents.map(_.id)) ++= rows
      runQuery(query)
    }
  }

  override def getWipReparentsForChangeSet(changeSetId: Int): Future[Seq[WipReparentRow]] = {
    val query = wipReparents
      .filter(_.changeSetId === changeSetId)
      .result
    runQuery(query)
  }

  override def getWipReparent(changeSetId: Int, haplogroupId: Int): Future[Option[WipReparentRow]] = {
    val query = wipReparents
      .filter(r => r.changeSetId === changeSetId && r.haplogroupId === haplogroupId)
      .result.headOption
    runQuery(query)
  }

  // ============================================================================
  // Cleanup Implementations
  // ============================================================================

  override def deleteWipDataForChangeSet(changeSetId: Int): Future[Int] = {
    // Tables have ON DELETE CASCADE, but we can also explicitly delete
    // Delete in order: variants, relationships, reparents, haplogroups
    val deleteVariants = wipHaplogroupVariants.filter(_.changeSetId === changeSetId).delete
    val deleteRelationships = wipRelationships.filter(_.changeSetId === changeSetId).delete
    val deleteReparents = wipReparents.filter(_.changeSetId === changeSetId).delete
    val deleteHaplogroups = wipHaplogroups.filter(_.changeSetId === changeSetId).delete

    val action = for {
      v <- deleteVariants
      rel <- deleteRelationships
      rep <- deleteReparents
      h <- deleteHaplogroups
    } yield v + rel + rep + h

    runQuery(action)
  }

  // ============================================================================
  // Statistics Implementations
  // ============================================================================

  override def getWipStatistics(changeSetId: Int): Future[WipStatistics] = {
    val countHaplogroups = wipHaplogroups.filter(_.changeSetId === changeSetId).length.result
    val countRelationships = wipRelationships.filter(_.changeSetId === changeSetId).length.result
    val countVariants = wipHaplogroupVariants.filter(_.changeSetId === changeSetId).length.result
    val countReparents = wipReparents.filter(_.changeSetId === changeSetId).length.result

    for {
      h <- runQuery(countHaplogroups)
      rel <- runQuery(countRelationships)
      v <- runQuery(countVariants)
      rep <- runQuery(countReparents)
    } yield WipStatistics(h, rel, v, rep)
  }
}
