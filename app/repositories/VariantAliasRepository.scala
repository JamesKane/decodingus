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
}
