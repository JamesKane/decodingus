package repositories

import jakarta.inject.Inject
import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api.*
import models.dal.domain.genomics.{VariantAlias, VariantAliasTable}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository for managing variant aliases.
 */
trait VariantAliasRepository {
  /**
   * Find all aliases for a variant.
   */
  def findByVariantId(variantId: Int): Future[Seq[VariantAlias]]

  /**
   * Find variants by alias value (searches across all alias types).
   */
  def findVariantIdsByAlias(aliasValue: String): Future[Seq[Int]]

  /**
   * Find variants by alias value and type.
   */
  def findVariantIdsByAliasAndType(aliasValue: String, aliasType: String): Future[Seq[Int]]

  /**
   * Add an alias to a variant. Returns true if added, false if already exists.
   */
  def addAlias(alias: VariantAlias): Future[Boolean]

  /**
   * Add multiple aliases in batch. Returns count of aliases added.
   */
  def addAliasesBatch(aliases: Seq[VariantAlias]): Future[Int]

  /**
   * Check if an alias exists for a variant.
   */
  def aliasExists(variantId: Int, aliasType: String, aliasValue: String): Future[Boolean]

  /**
   * Set an alias as primary for its type (unsets other primaries of same type for the variant).
   */
  def setPrimary(variantId: Int, aliasType: String, aliasValue: String): Future[Boolean]

  /**
   * Delete an alias.
   */
  def deleteAlias(variantId: Int, aliasType: String, aliasValue: String): Future[Boolean]

  /**
   * Search aliases by partial match.
   */
  def searchAliases(query: String, limit: Int): Future[Seq[VariantAlias]]

  /**
   * Find aliases for multiple variants in batch.
   * Returns a map of variantId -> Seq[VariantAlias]
   */
  def findByVariantIds(variantIds: Seq[Int]): Future[Map[Int, Seq[VariantAlias]]]

  /**
   * Bulk update source for aliases matching a prefix pattern.
   * Used to fix migration data where source was not properly attributed.
   *
   * @param aliasPrefix  The prefix to match (e.g., "FGC" matches "FGC29071")
   * @param newSource    The new source value (e.g., "FGC")
   * @param oldSource    Optional: only update aliases with this current source (e.g., "migration")
   * @return Number of aliases updated
   */
  def bulkUpdateSourceByPrefix(aliasPrefix: String, newSource: String, oldSource: Option[String]): Future[Int]

  /**
   * Get distinct sources currently in the database.
   */
  def getDistinctSources(): Future[Seq[String]]

  /**
   * Count aliases by source.
   */
  def countBySource(source: String): Future[Int]

  /**
   * Count aliases matching a prefix with a specific source.
   */
  def countByPrefixAndSource(aliasPrefix: String, source: String): Future[Int]
}

class VariantAliasRepositoryImpl @Inject()(
    protected val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
    extends VariantAliasRepository
    with HasDatabaseConfigProvider[MyPostgresProfile] {

  import models.dal.DatabaseSchema.domain.genomics.variantAliases

  override def findByVariantId(variantId: Int): Future[Seq[VariantAlias]] = {
    db.run(
      variantAliases
        .filter(_.variantId === variantId)
        .sortBy(a => (a.aliasType, a.isPrimary.desc))
        .result
    )
  }

  override def findVariantIdsByAlias(aliasValue: String): Future[Seq[Int]] = {
    val upperValue = aliasValue.toUpperCase
    db.run(
      variantAliases
        .filter(_.aliasValue.toUpperCase === upperValue)
        .map(_.variantId)
        .distinct
        .result
    )
  }

  override def findVariantIdsByAliasAndType(aliasValue: String, aliasType: String): Future[Seq[Int]] = {
    val upperValue = aliasValue.toUpperCase
    db.run(
      variantAliases
        .filter(a => a.aliasValue.toUpperCase === upperValue && a.aliasType === aliasType)
        .map(_.variantId)
        .distinct
        .result
    )
  }

  override def addAlias(alias: VariantAlias): Future[Boolean] = {
    val insertAction = variantAliases += alias
    db.run(insertAction.asTry).map(_.isSuccess)
  }

  override def addAliasesBatch(aliases: Seq[VariantAlias]): Future[Int] = {
    if (aliases.isEmpty) {
      Future.successful(0)
    } else {
      // Use insertOrUpdate to handle conflicts gracefully
      val actions = aliases.map { alias =>
        sql"""
          INSERT INTO variant_alias (variant_id, alias_type, alias_value, source, is_primary, created_at)
          VALUES (${alias.variantId}, ${alias.aliasType}, ${alias.aliasValue}, ${alias.source}, ${alias.isPrimary}, NOW())
          ON CONFLICT (variant_id, alias_type, alias_value) DO NOTHING
        """.asUpdate
      }
      db.run(DBIO.sequence(actions).transactionally).map(_.sum)
    }
  }

  override def aliasExists(variantId: Int, aliasType: String, aliasValue: String): Future[Boolean] = {
    db.run(
      variantAliases
        .filter(a => a.variantId === variantId && a.aliasType === aliasType && a.aliasValue === aliasValue)
        .exists
        .result
    )
  }

  override def setPrimary(variantId: Int, aliasType: String, aliasValue: String): Future[Boolean] = {
    val action = for {
      // First, unset all primaries of this type for this variant
      _ <- variantAliases
        .filter(a => a.variantId === variantId && a.aliasType === aliasType)
        .map(_.isPrimary)
        .update(false)
      // Then set the specified one as primary
      updated <- variantAliases
        .filter(a => a.variantId === variantId && a.aliasType === aliasType && a.aliasValue === aliasValue)
        .map(_.isPrimary)
        .update(true)
    } yield updated > 0

    db.run(action.transactionally)
  }

  override def deleteAlias(variantId: Int, aliasType: String, aliasValue: String): Future[Boolean] = {
    db.run(
      variantAliases
        .filter(a => a.variantId === variantId && a.aliasType === aliasType && a.aliasValue === aliasValue)
        .delete
    ).map(_ > 0)
  }

  override def searchAliases(query: String, limit: Int): Future[Seq[VariantAlias]] = {
    val upperQuery = query.toUpperCase
    db.run(
      variantAliases
        .filter(_.aliasValue.toUpperCase like s"%$upperQuery%")
        .sortBy(_.aliasValue)
        .take(limit)
        .result
    )
  }

  override def findByVariantIds(variantIds: Seq[Int]): Future[Map[Int, Seq[VariantAlias]]] = {
    if (variantIds.isEmpty) {
      Future.successful(Map.empty)
    } else {
      db.run(
        variantAliases
          .filter(_.variantId inSet variantIds)
          .sortBy(a => (a.variantId, a.aliasType, a.isPrimary.desc))
          .result
      ).map(_.groupBy(_.variantId))
    }
  }

  override def bulkUpdateSourceByPrefix(aliasPrefix: String, newSource: String, oldSource: Option[String]): Future[Int] = {
    val upperPrefix = aliasPrefix.toUpperCase
    val updateQuery = oldSource match {
      case Some(oldSrc) =>
        sql"""
          UPDATE variant_alias
          SET source = $newSource
          WHERE UPPER(alias_value) LIKE ${upperPrefix + "%"}
            AND (source = $oldSrc OR source IS NULL)
        """.asUpdate
      case None =>
        sql"""
          UPDATE variant_alias
          SET source = $newSource
          WHERE UPPER(alias_value) LIKE ${upperPrefix + "%"}
        """.asUpdate
    }
    db.run(updateQuery)
  }

  override def getDistinctSources(): Future[Seq[String]] = {
    db.run(
      variantAliases
        .map(_.source)
        .distinct
        .result
    ).map(_.flatten)
  }

  override def countBySource(source: String): Future[Int] = {
    db.run(
      variantAliases
        .filter(_.source === source)
        .length
        .result
    )
  }

  override def countByPrefixAndSource(aliasPrefix: String, source: String): Future[Int] = {
    val upperPrefix = aliasPrefix.toUpperCase
    db.run(
      sql"""
        SELECT COUNT(*)
        FROM variant_alias
        WHERE UPPER(alias_value) LIKE ${upperPrefix + "%"}
          AND source = $source
      """.as[Int].head
    )
  }
}
