package repositories

import jakarta.inject.Inject
import models.*
import models.domain.genomics.{MutationType, NamingStatus, VariantV2}
import models.domain.haplogroups.{Haplogroup, HaplogroupVariant}
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

/**
 * Trait for managing and querying relationships between haplogroups and genetic variants.
 */
trait HaplogroupVariantRepository {
  /**
   * Finds and retrieves a list of variants based on the given query string.
   *
   * @param query The search query used to filter and retrieve the relevant variants.
   * @return A future containing a sequence of variants that match the provided query.
   */
  def findVariants(query: String): Future[Seq[VariantV2]]

  /**
   * Retrieves the list of variants associated with a given haplogroup.
   *
   * @param haplogroupId the identifier of the haplogroup for which variants are to be retrieved
   * @return a future containing a sequence of VariantV2 objects
   */
  def getHaplogroupVariants(haplogroupId: Int): Future[Seq[VariantV2]]

  def countHaplogroupVariants(haplogroupId: Long): Future[Int]

  /**
   * Retrieves a list of genetic variants associated with the given haplogroup.
   *
   * @param haplogroupId The unique identifier of the haplogroup for which the variants are being requested.
   * @return A Future containing a sequence of VariantV2 objects associated with the specified haplogroup.
   */
  def getVariantsByHaplogroup(haplogroupId: Int): Future[Seq[VariantV2]]

  /**
   * Retrieves a list of haplogroups associated with the specified variant.
   *
   * @param variantId The unique identifier of the variant for which haplogroups are to be retrieved.
   * @return A Future containing a sequence of Haplogroup objects associated with the specified variant.
   */
  def getHaplogroupsByVariant(variantId: Int): Future[Seq[Haplogroup]]

  /**
   * Associates a genetic variant with a specified haplogroup.
   *
   * @param haplogroupId The unique identifier of the haplogroup to which the variant will be added.
   * @param variantId    The unique identifier of the genetic variant to add to the haplogroup.
   * @return A Future containing the number of records updated or affected (typically 1 if successful, 0 otherwise).
   */
  def addVariantToHaplogroup(haplogroupId: Int, variantId: Int): Future[Int]

  /**
   * Retrieves the `haplogroup_variant_id`s for a given haplogroup.
   *
   * @param haplogroupId The unique identifier of the haplogroup.
   * @return A Future containing a sequence of `haplogroup_variant_id`s.
   */
  def getHaplogroupVariantIds(haplogroupId: Int): Future[Seq[Int]]

  /**
   * Removes a specified variant from a given haplogroup.
   *
   * @param haplogroupId The unique identifier of the haplogroup from which the variant will be removed.
   * @param variantId    The unique identifier of the variant to be removed from the haplogroup.
   * @return A future containing the number of records affected by the operation.
   */
  def removeVariantFromHaplogroup(haplogroupId: Int, variantId: Int): Future[Int]

  /**
   * Finds haplogroups associated with a given defining variant.
   *
   * @param variantId      The identifier of the defining variant used to locate haplogroups.
   * @param haplogroupType The type of haplogroup to be returned, indicating the classification context.
   * @return A Future containing a sequence of haplogroups that match the given defining variant and type.
   */
  def findHaplogroupsByDefiningVariant(variantId: String, haplogroupType: HaplogroupType): Future[Seq[Haplogroup]]

  /**
   * Retrieves variants associated with a haplogroup by its name.
   *
   * @param haplogroupName The name of the haplogroup (e.g., "R-M269")
   * @return A Future containing a sequence of VariantV2 for the haplogroup
   */
  def getVariantsByHaplogroupName(haplogroupName: String): Future[Seq[VariantV2]]

  /**
   * Retrieves variants for multiple haplogroups in a single query.
   *
   * @param haplogroupIds The sequence of haplogroup IDs to retrieve variants for.
   * @return A Future containing a sequence of (haplogroupId, VariantV2) tuples.
   */
  def getVariantsForHaplogroups(haplogroupIds: Seq[Int]): Future[Seq[(Int, VariantV2)]]

  /**
   * Bulk associate variants with haplogroups in a single operation.
   * Uses ON CONFLICT to handle duplicates gracefully.
   *
   * @param associations Sequence of (haplogroupId, variantId) tuples
   * @return A Future containing the sequence of haplogroup_variant_ids created or found
   */
  def bulkAddVariantsToHaplogroups(associations: Seq[(Int, Int)]): Future[Seq[Int]]

  /**
   * Bulk remove variant associations from a haplogroup.
   *
   * @param haplogroupId The haplogroup to remove variants from
   * @param variantIds   The variant IDs to remove
   * @return A Future containing the number of associations removed
   */
  def bulkRemoveVariantsFromHaplogroup(haplogroupId: Int, variantIds: Seq[Int]): Future[Int]

  /**
   * Gets the variant IDs (not haplogroup_variant_ids) for a haplogroup.
   *
   * @param haplogroupId The haplogroup ID
   * @return A Future containing the sequence of variant IDs
   */
  def getVariantIdsForHaplogroup(haplogroupId: Int): Future[Seq[Int]]
}

class HaplogroupVariantRepositoryImpl @Inject()(
                                                 dbConfigProvider: DatabaseConfigProvider
                                               )(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with HaplogroupVariantRepository {

  import models.dal.DatabaseSchema.domain.haplogroups.{haplogroupVariants, haplogroups}
  import models.dal.MyPostgresProfile.api.*
  import models.dal.domain.genomics.VariantV2Table
  import play.api.libs.json.Json
  import slick.jdbc.GetResult

  private val variantsV2 = TableQuery[VariantV2Table]

  // GetResult for raw SQL queries
  private implicit val variantV2GetResult: GetResult[VariantV2] = GetResult { r =>
    VariantV2(
      variantId = Some(r.nextInt()),
      canonicalName = r.nextStringOption(),
      mutationType = MutationType.fromStringOrDefault(r.nextString()),
      namingStatus = NamingStatus.fromStringOrDefault(r.nextString()),
      aliases = Json.parse(r.nextString()),
      coordinates = Json.parse(r.nextString()),
      definingHaplogroupId = r.nextIntOption(),
      evidence = Json.parse(r.nextString()),
      primers = Json.parse(r.nextString()),
      notes = r.nextStringOption(),
      createdAt = r.nextTimestamp().toInstant,
      updatedAt = r.nextTimestamp().toInstant
    )
  }

  override def findVariants(query: String): Future[Seq[VariantV2]] = {
    val normalizedQuery = query.trim.toLowerCase
    val upperQuery = normalizedQuery.toUpperCase
    val searchPattern = s"%$upperQuery%"

    // Handle different query formats
    if (normalizedQuery.startsWith("rs")) {
      // Search rs_ids in aliases
      val rsQuery = sql"""
        SELECT * FROM variant_v2
        WHERE aliases->'rs_ids' ?? $normalizedQuery
      """.as[VariantV2]
      runQuery(rsQuery)
    } else if (normalizedQuery.contains(":")) {
      // Coordinate-based search (contig:position or contig:position:ref:alt)
      val parts = normalizedQuery.split(":")
      parts.length match {
        case 2 =>
          val contig = parts(0)
          val position = parts(1).toIntOption.getOrElse(0)
          val coordQuery = sql"""
            SELECT * FROM variant_v2
            WHERE EXISTS (
              SELECT 1 FROM jsonb_each(coordinates) AS c(ref_genome, coords)
              WHERE coords->>'contig' ILIKE $contig
                AND (coords->>'position')::int = $position
            )
          """.as[VariantV2]
          runQuery(coordQuery)
        case 4 =>
          val contig = parts(0)
          val position = parts(1).toIntOption.getOrElse(0)
          val ref = parts(2).toUpperCase
          val alt = parts(3).toUpperCase
          val coordQuery = sql"""
            SELECT * FROM variant_v2
            WHERE EXISTS (
              SELECT 1 FROM jsonb_each(coordinates) AS c(ref_genome, coords)
              WHERE coords->>'contig' ILIKE $contig
                AND (coords->>'position')::int = $position
                AND UPPER(coords->>'ref') = $ref
                AND UPPER(coords->>'alt') = $alt
            )
          """.as[VariantV2]
          runQuery(coordQuery)
        case _ =>
          Future.successful(Seq.empty)
      }
    } else {
      // Search by canonical name or aliases
      val nameQuery = sql"""
        SELECT * FROM variant_v2
        WHERE UPPER(canonical_name) LIKE $searchPattern
           OR aliases->'common_names' ?? $normalizedQuery
           OR EXISTS (
             SELECT 1 FROM jsonb_array_elements_text(aliases->'common_names') AS name
             WHERE UPPER(name) LIKE $searchPattern
           )
        LIMIT 100
      """.as[VariantV2]
      runQuery(nameQuery)
    }
  }

  override def getHaplogroupVariants(haplogroupId: Int): Future[Seq[VariantV2]] = {
    val query = for {
      hv <- haplogroupVariants if hv.haplogroupId === haplogroupId
      v <- variantsV2 if v.variantId === hv.variantId
    } yield v

    runQuery(query.result)
  }

  def countHaplogroupVariants(haplogroupId: Long): Future[Int] = {
    val q = for {
      hv <- haplogroupVariants if hv.haplogroupId === haplogroupId.toInt
      v <- variantsV2 if hv.variantId === v.variantId
    } yield v.canonicalName

    runQuery(q.distinct.length.result)
  }

  override def getVariantsByHaplogroup(haplogroupId: Int): Future[Seq[VariantV2]] = {
    val query = for {
      hv <- haplogroupVariants if hv.haplogroupId === haplogroupId
      variant <- variantsV2 if variant.variantId === hv.variantId
    } yield variant

    runQuery(query.result)
  }

  override def getHaplogroupsByVariant(variantId: Int): Future[Seq[Haplogroup]] = {
    val query = for {
      hv <- haplogroupVariants if hv.variantId === variantId
      haplogroup <- haplogroups if haplogroup.haplogroupId === hv.haplogroupId
    } yield haplogroup

    runQuery(query.result)
  }

  override def addVariantToHaplogroup(haplogroupId: Int, variantId: Int): Future[Int] = {
    val insertAction = sql"""
      INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id)
      VALUES ($haplogroupId, $variantId)
      ON CONFLICT (haplogroup_id, variant_id) DO UPDATE SET haplogroup_id = EXCLUDED.haplogroup_id -- No actual update needed, just to trigger RETURNING
      RETURNING haplogroup_variant_id
    """.as[Int].head

    runQuery(insertAction)
  }

  override def getHaplogroupVariantIds(haplogroupId: Int): Future[Seq[Int]] = {
    val query = haplogroupVariants.filter(_.haplogroupId === haplogroupId).map(_.haplogroupVariantId)
    runQuery(query.result)
  }

  def removeVariantFromHaplogroup(haplogroupId: Int, variantId: Int): Future[Int] = {
    val query = haplogroupVariants
      .filter(hv => hv.haplogroupId === haplogroupId && hv.variantId === variantId)
      .delete

    runQuery(query)
  }

  override def findHaplogroupsByDefiningVariant(variantId: String, haplogroupType: HaplogroupType): Future[Seq[Haplogroup]] = {
    // Search by canonical name or variant ID
    val variantIdOpt = variantId.toIntOption

    val query = variantIdOpt match {
      case Some(vid) =>
        for {
          variant <- variantsV2 if variant.variantId === vid || variant.canonicalName === variantId
          haplogroupVariant <- haplogroupVariants if haplogroupVariant.variantId === variant.variantId
          haplogroup <- haplogroups if
            haplogroup.haplogroupId === haplogroupVariant.haplogroupId &&
              haplogroup.haplogroupType === haplogroupType
        } yield haplogroup
      case None =>
        for {
          variant <- variantsV2 if variant.canonicalName === variantId
          haplogroupVariant <- haplogroupVariants if haplogroupVariant.variantId === variant.variantId
          haplogroup <- haplogroups if
            haplogroup.haplogroupId === haplogroupVariant.haplogroupId &&
              haplogroup.haplogroupType === haplogroupType
        } yield haplogroup
    }

    runQuery(query.result)
  }

  override def getVariantsByHaplogroupName(haplogroupName: String): Future[Seq[VariantV2]] = {
    val query = for {
      hg <- haplogroups if hg.name === haplogroupName
      hv <- haplogroupVariants if hv.haplogroupId === hg.haplogroupId
      v <- variantsV2 if v.variantId === hv.variantId
    } yield v

    runQuery(query.result)
  }

  override def getVariantsForHaplogroups(haplogroupIds: Seq[Int]): Future[Seq[(Int, VariantV2)]] = {
    val query = for {
      hv <- haplogroupVariants if hv.haplogroupId.inSet(haplogroupIds)
      v <- variantsV2 if v.variantId === hv.variantId
    } yield (hv.haplogroupId, v)

    runQuery(query.result)
  }

  override def bulkAddVariantsToHaplogroups(associations: Seq[(Int, Int)]): Future[Seq[Int]] = {
    if (associations.isEmpty) return Future.successful(Seq.empty)

    // Build values clause for bulk insert
    val valuesClause = associations.map { case (hgId, varId) =>
      s"($hgId, $varId)"
    }.mkString(", ")

    val insertQuery = sql"""
      INSERT INTO tree.haplogroup_variant (haplogroup_id, variant_id)
      VALUES #$valuesClause
      ON CONFLICT (haplogroup_id, variant_id) DO UPDATE
        SET haplogroup_id = EXCLUDED.haplogroup_id
      RETURNING haplogroup_variant_id
    """.as[Int]

    runQuery(insertQuery)
  }

  override def bulkRemoveVariantsFromHaplogroup(haplogroupId: Int, variantIds: Seq[Int]): Future[Int] = {
    if (variantIds.isEmpty) return Future.successful(0)

    val query = haplogroupVariants
      .filter(hv => hv.haplogroupId === haplogroupId && hv.variantId.inSet(variantIds))
      .delete

    runQuery(query)
  }

  override def getVariantIdsForHaplogroup(haplogroupId: Int): Future[Seq[Int]] = {
    val query = haplogroupVariants.filter(_.haplogroupId === haplogroupId).map(_.variantId)
    runQuery(query.result)
  }
}