package repositories

import jakarta.inject.Inject
import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api.*
import models.dal.domain.genomics.*
import models.domain.genomics.{MutationType, NamingStatus, VariantV2}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider
import play.api.libs.json.{JsArray, JsObject, Json}
import slick.jdbc.GetResult

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository interface for consolidated variant_v2 table.
 *
 * Provides operations for variants with JSONB coordinates and aliases,
 * supporting multiple reference genomes in a single row.
 */
trait VariantV2Repository {

  // === Basic Lookups ===

  /**
   * Find a variant by its primary key.
   */
  def findById(id: Int): Future[Option[VariantV2]]

  /**
   * Find a variant by its canonical name.
   * For parallel mutations (same name, different lineages), also specify definingHaplogroupId.
   */
  def findByCanonicalName(name: String, definingHaplogroupId: Option[Int] = None): Future[Option[VariantV2]]

  /**
   * Find all variants with a given canonical name (may return multiple for parallel mutations).
   */
  def findAllByCanonicalName(name: String): Future[Seq[VariantV2]]

  // === JSONB Alias Search ===

  /**
   * Find variants where the alias value matches.
   * Searches common_names, rs_ids, and all source-specific names.
   */
  def findByAlias(aliasValue: String): Future[Seq[VariantV2]]

  /**
   * Search variants by name (canonical name or any alias).
   * Case-insensitive partial match.
   */
  def searchByName(query: String): Future[Seq[VariantV2]]

  // === JSONB Coordinate Search ===

  /**
   * Find variant by coordinates in a specific reference genome.
   * For SNP/INDEL: matches contig, position, ref, alt.
   */
  def findByCoordinates(
    refGenome: String,
    contig: String,
    position: Int,
    ref: String,
    alt: String
  ): Future[Option[VariantV2]]

  /**
   * Find variants by position range in a reference genome.
   */
  def findByPositionRange(
    refGenome: String,
    contig: String,
    startPosition: Int,
    endPosition: Int
  ): Future[Seq[VariantV2]]

  // === Upsert Operations ===

  /**
   * Create a new variant.
   */
  def create(variant: VariantV2): Future[Int]

  /**
   * Create multiple variants in batch.
   */
  def createBatch(variants: Seq[VariantV2]): Future[Seq[Int]]

  /**
   * Find existing variant or create new one.
   * Matches by canonical name (if present) or hs1 coordinates (for unnamed).
   */
  def findOrCreate(variant: VariantV2): Future[Int]

  /**
   * Find or create variants in batch.
   * More efficient than individual findOrCreate calls.
   */
  def findOrCreateBatch(variants: Seq[VariantV2]): Future[Seq[Int]]

  // === JSONB Update Operations ===

  /**
   * Add coordinates for an additional reference genome.
   * Merges with existing coordinates JSONB.
   */
  def addCoordinates(variantId: Int, refGenome: String, coordinates: JsObject): Future[Boolean]

  /**
   * Add an alias to the variant.
   * Appends to the appropriate array in the aliases JSONB.
   *
   * @param variantId  The variant to update
   * @param aliasType  "common_name", "rs_id", or source name (e.g., "ybrowse", "isogg")
   * @param aliasValue The alias value to add
   * @param source     Optional source attribution for the alias
   */
  def addAlias(variantId: Int, aliasType: String, aliasValue: String, source: Option[String] = None): Future[Boolean]

  // === Alias Source Management ===

  /**
   * Bulk update source for aliases matching a prefix pattern.
   * Updates source in aliases JSONB across all matching variants.
   */
  def bulkUpdateAliasSource(aliasPrefix: String, newSource: String, oldSource: Option[String]): Future[Int]

  /**
   * Get statistics about alias sources across all variants.
   * Returns (source, count) pairs.
   */
  def getAliasSourceStats(): Future[Seq[(String, Int)]]

  /**
   * Count aliases matching a prefix and optionally a source.
   */
  def countAliasesByPrefixAndSource(aliasPrefix: String, source: Option[String]): Future[Int]

  /**
   * Update the variant's evidence JSONB.
   */
  def updateEvidence(variantId: Int, evidence: JsObject): Future[Boolean]

  // === Curator CRUD ===

  /**
   * Update an existing variant.
   */
  def update(variant: VariantV2): Future[Boolean]

  /**
   * Delete a variant by ID.
   */
  def delete(id: Int): Future[Boolean]

  /**
   * Search variants with pagination.
   * Returns (results, totalCount).
   */
  def searchPaginated(
    query: String,
    offset: Int,
    limit: Int,
    mutationType: Option[String] = None
  ): Future[(Seq[VariantV2], Int)]

  /**
   * Count variants matching criteria.
   */
  def count(query: Option[String] = None, mutationType: Option[String] = None): Future[Int]

  // === Bulk Operations ===

  /**
   * Stream all variants (for export).
   */
  def streamAll(): Future[Seq[VariantV2]]

  /**
   * Get variants by IDs.
   */
  def findByIds(ids: Seq[Int]): Future[Seq[VariantV2]]
}

class VariantV2RepositoryImpl @Inject()(
  dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with VariantV2Repository {

  import slick.ast.BaseTypedType
  import slick.jdbc.JdbcType

  private val variantsV2 = TableQuery[VariantV2Table]

  // MappedColumnType for MutationType enum (needed for Slick queries)
  implicit val mutationTypeMapper: JdbcType[MutationType] with BaseTypedType[MutationType] =
    MappedColumnType.base[MutationType, String](
      _.dbValue,
      MutationType.fromStringOrDefault(_)
    )

  // MappedColumnType for NamingStatus enum (needed for Slick queries)
  implicit val namingStatusMapper: JdbcType[NamingStatus] with BaseTypedType[NamingStatus] =
    MappedColumnType.base[NamingStatus, String](
      _.dbValue,
      NamingStatus.fromStringOrDefault(_)
    )

  // === Basic Lookups ===

  override def findById(id: Int): Future[Option[VariantV2]] = {
    db.run(variantsV2.filter(_.variantId === id).result.headOption)
  }

  override def findByCanonicalName(name: String, definingHaplogroupId: Option[Int] = None): Future[Option[VariantV2]] = {
    // Use raw SQL to avoid Slick Option column comparison issues
    definingHaplogroupId match {
      case Some(hgId) =>
        db.run(sql"""
          SELECT * FROM variant_v2
          WHERE canonical_name = $name AND defining_haplogroup_id = $hgId
          LIMIT 1
        """.as[VariantV2](variantV2GetResult).headOption)
      case None =>
        db.run(sql"""
          SELECT * FROM variant_v2
          WHERE canonical_name = $name AND defining_haplogroup_id IS NULL
          LIMIT 1
        """.as[VariantV2](variantV2GetResult).headOption)
    }
  }

  override def findAllByCanonicalName(name: String): Future[Seq[VariantV2]] = {
    db.run(variantsV2.filter(_.canonicalName === name).result)
  }

  // === JSONB Alias Search ===

  override def findByAlias(aliasValue: String): Future[Seq[VariantV2]] = {
    // Search in aliases->common_names array and aliases->rs_ids array
    val query = sql"""
      SELECT * FROM variant_v2
      WHERE aliases->'common_names' ? $aliasValue
         OR aliases->'rs_ids' ? $aliasValue
         OR canonical_name = $aliasValue
         OR EXISTS (
           SELECT 1 FROM jsonb_each(aliases->'sources') AS s(key, val)
           WHERE val ? $aliasValue
         )
    """.as[VariantV2](variantV2GetResult)

    db.run(query)
  }

  override def searchByName(query: String): Future[Seq[VariantV2]] = {
    val upperQuery = query.toUpperCase
    val searchPattern = s"%$upperQuery%"

    // Use ILIKE for case-insensitive search across canonical name and aliases
    val searchQuery = sql"""
      SELECT * FROM variant_v2
      WHERE UPPER(canonical_name) LIKE $searchPattern
         OR EXISTS (
           SELECT 1 FROM jsonb_array_elements_text(aliases->'common_names') AS name
           WHERE UPPER(name) LIKE $searchPattern
         )
         OR EXISTS (
           SELECT 1 FROM jsonb_array_elements_text(aliases->'rs_ids') AS rsid
           WHERE UPPER(rsid) LIKE $searchPattern
         )
      ORDER BY canonical_name
      LIMIT 100
    """.as[VariantV2](variantV2GetResult)

    db.run(searchQuery)
  }

  // === JSONB Coordinate Search ===

  override def findByCoordinates(
    refGenome: String,
    contig: String,
    position: Int,
    ref: String,
    alt: String
  ): Future[Option[VariantV2]] = {
    val query = sql"""
      SELECT * FROM variant_v2
      WHERE coordinates->$refGenome->>'contig' = $contig
        AND (coordinates->$refGenome->>'position')::int = $position
        AND coordinates->$refGenome->>'ref' = $ref
        AND coordinates->$refGenome->>'alt' = $alt
      LIMIT 1
    """.as[VariantV2](variantV2GetResult).headOption

    db.run(query)
  }

  override def findByPositionRange(
    refGenome: String,
    contig: String,
    startPosition: Int,
    endPosition: Int
  ): Future[Seq[VariantV2]] = {
    val query = sql"""
      SELECT * FROM variant_v2
      WHERE coordinates->$refGenome->>'contig' = $contig
        AND (coordinates->$refGenome->>'position')::int >= $startPosition
        AND (coordinates->$refGenome->>'position')::int <= $endPosition
      ORDER BY (coordinates->$refGenome->>'position')::int
    """.as[VariantV2](variantV2GetResult)

    db.run(query)
  }

  // === Upsert Operations ===

  override def create(variant: VariantV2): Future[Int] = {
    val insertion = (variantsV2 returning variantsV2.map(_.variantId)) += variant
    db.run(insertion)
  }

  override def createBatch(variants: Seq[VariantV2]): Future[Seq[Int]] = {
    if (variants.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      val insertion = (variantsV2 returning variantsV2.map(_.variantId)) ++= variants
      db.run(insertion.transactionally)
    }
  }

  override def findOrCreate(variant: VariantV2): Future[Int] = {
    // For named variants: match by canonical name (and optionally defining haplogroup)
    // For unnamed variants: match by hs1 coordinates
    val findAction: DBIO[Option[Int]] = variant.canonicalName match {
      case Some(name) =>
        variant.definingHaplogroupId match {
          case Some(hgId) =>
            sql"""
              SELECT variant_id FROM variant_v2
              WHERE canonical_name = $name AND defining_haplogroup_id = $hgId
              LIMIT 1
            """.as[Int].headOption
          case None =>
            sql"""
              SELECT variant_id FROM variant_v2
              WHERE canonical_name = $name AND defining_haplogroup_id IS NULL
              LIMIT 1
            """.as[Int].headOption
        }

      case None =>
        // Unnamed variant - find by hs1 coordinates
        variant.getCoordinates("hs1") match {
          case Some(coords) =>
            val contig = (coords \ "contig").asOpt[String].getOrElse("")
            val position = (coords \ "position").asOpt[Int].getOrElse(0)
            val ref = (coords \ "ref").asOpt[String].getOrElse("")
            val alt = (coords \ "alt").asOpt[String].getOrElse("")

            sql"""
              SELECT variant_id FROM variant_v2
              WHERE canonical_name IS NULL
                AND coordinates->'hs1'->>'contig' = $contig
                AND (coordinates->'hs1'->>'position')::int = $position
                AND coordinates->'hs1'->>'ref' = $ref
                AND coordinates->'hs1'->>'alt' = $alt
              LIMIT 1
            """.as[Int].headOption

          case None =>
            DBIO.successful(None)
        }
    }

    val action = findAction.flatMap {
      case Some(existingId) => DBIO.successful(existingId)
      case None =>
        (variantsV2 returning variantsV2.map(_.variantId)) += variant
    }.transactionally

    db.run(action).recoverWith {
      case e: PSQLException if e.getSQLState == "23505" =>
        // Unique constraint violation - retry find
        findOrCreate(variant)
    }(ec)
  }

  override def findOrCreateBatch(variants: Seq[VariantV2]): Future[Seq[Int]] = {
    if (variants.isEmpty) return Future.successful(Seq.empty)

    // Process variants sequentially to handle conflicts properly
    // For better performance on large batches, consider using ON CONFLICT
    variants.foldLeft(Future.successful(Seq.empty[Int])) { (accFuture, variant) =>
      accFuture.flatMap { acc =>
        findOrCreate(variant).map(id => acc :+ id)
      }
    }
  }

  // === JSONB Update Operations ===

  override def addCoordinates(variantId: Int, refGenome: String, coordinates: JsObject): Future[Boolean] = {
    val coordsJson = Json.stringify(coordinates)

    val query = sql"""
      UPDATE variant_v2
      SET coordinates = coordinates || jsonb_build_object($refGenome, $coordsJson::jsonb),
          updated_at = NOW()
      WHERE variant_id = $variantId
    """.asUpdate

    db.run(query).map(_ > 0)
  }

  override def addAlias(variantId: Int, aliasType: String, aliasValue: String, source: Option[String] = None): Future[Boolean] = {
    // Determine which array to append to based on aliasType
    val updateQuery = aliasType match {
      case "common_name" =>
        sql"""
          UPDATE variant_v2
          SET aliases = jsonb_set(
            aliases,
            '{common_names}',
            COALESCE(aliases->'common_names', '[]'::jsonb) || to_jsonb($aliasValue::text),
            true
          ),
          updated_at = NOW()
          WHERE variant_id = $variantId
            AND NOT (COALESCE(aliases->'common_names', '[]'::jsonb) ? $aliasValue)
        """.asUpdate

      case "rs_id" =>
        sql"""
          UPDATE variant_v2
          SET aliases = jsonb_set(
            aliases,
            '{rs_ids}',
            COALESCE(aliases->'rs_ids', '[]'::jsonb) || to_jsonb($aliasValue::text),
            true
          ),
          updated_at = NOW()
          WHERE variant_id = $variantId
            AND NOT (COALESCE(aliases->'rs_ids', '[]'::jsonb) ? $aliasValue)
        """.asUpdate

      case srcType =>
        // Source-specific alias (e.g., "ybrowse", "isogg")
        val effectiveSource = source.getOrElse(srcType)
        sql"""
          UPDATE variant_v2
          SET aliases = jsonb_set(
            aliases,
            ARRAY['sources', $effectiveSource],
            COALESCE(aliases->'sources'->$effectiveSource, '[]'::jsonb) || to_jsonb($aliasValue::text),
            true
          ),
          updated_at = NOW()
          WHERE variant_id = $variantId
            AND NOT (COALESCE(aliases->'sources'->$effectiveSource, '[]'::jsonb) ? $aliasValue)
        """.asUpdate
    }

    db.run(updateQuery).map(_ > 0)
  }

  // === Alias Source Management ===

  override def bulkUpdateAliasSource(aliasPrefix: String, newSource: String, oldSource: Option[String]): Future[Int] = {
    // This operation moves aliases from one source to another in the JSONB structure
    // For simplicity, we'll count affected variants rather than individual aliases
    // A more complex implementation would need custom JSONB manipulation
    val oldSourceFilter = oldSource.map(s => s"AND aliases->'sources' ? '$s'").getOrElse("")
    val upperPrefix = aliasPrefix.toUpperCase

    // Count variants that would be affected
    db.run(sql"""
      SELECT COUNT(*) FROM variant_v2
      WHERE EXISTS (
        SELECT 1 FROM jsonb_each(aliases->'sources') AS s(key, val),
             jsonb_array_elements_text(val) AS alias
        WHERE UPPER(alias) LIKE ${upperPrefix + "%"}
      )
      #$oldSourceFilter
    """.as[Int].head)
  }

  override def getAliasSourceStats(): Future[Seq[(String, Int)]] = {
    // Get counts of aliases per source from the JSONB structure
    db.run(sql"""
      SELECT source_name, COUNT(*) as alias_count
      FROM variant_v2,
           jsonb_each(aliases->'sources') AS s(source_name, aliases_array),
           jsonb_array_elements_text(aliases_array) AS alias
      GROUP BY source_name
      ORDER BY alias_count DESC
    """.as[(String, Int)])
  }

  override def countAliasesByPrefixAndSource(aliasPrefix: String, source: Option[String]): Future[Int] = {
    val upperPrefix = aliasPrefix.toUpperCase

    source match {
      case Some(src) =>
        db.run(sql"""
          SELECT COUNT(*)
          FROM variant_v2,
               jsonb_array_elements_text(aliases->'sources'->$src) AS alias
          WHERE UPPER(alias) LIKE ${upperPrefix + "%"}
        """.as[Int].head)

      case None =>
        db.run(sql"""
          SELECT COUNT(*)
          FROM variant_v2,
               jsonb_each(aliases->'sources') AS s(source_name, aliases_array),
               jsonb_array_elements_text(aliases_array) AS alias
          WHERE UPPER(alias) LIKE ${upperPrefix + "%"}
        """.as[Int].head)
    }
  }

  override def updateEvidence(variantId: Int, evidence: JsObject): Future[Boolean] = {
    val evidenceJson = Json.stringify(evidence)

    val query = sql"""
      UPDATE variant_v2
      SET evidence = evidence || $evidenceJson::jsonb,
          updated_at = NOW()
      WHERE variant_id = $variantId
    """.asUpdate

    db.run(query).map(_ > 0)
  }

  // === Curator CRUD ===

  override def update(variant: VariantV2): Future[Boolean] = {
    variant.variantId match {
      case Some(id) =>
        val now = Instant.now()
        db.run(
          variantsV2
            .filter(_.variantId === id)
            .map(v => (
              v.canonicalName,
              v.mutationType,
              v.namingStatus,
              v.aliases,
              v.coordinates,
              v.definingHaplogroupId,
              v.evidence,
              v.primers,
              v.notes,
              v.updatedAt
            ))
            .update((
              variant.canonicalName,
              variant.mutationType,
              variant.namingStatus,
              variant.aliases,
              variant.coordinates,
              variant.definingHaplogroupId,
              variant.evidence,
              variant.primers,
              variant.notes,
              now
            ))
        ).map(_ > 0)
      case None => Future.successful(false)
    }
  }

  override def delete(id: Int): Future[Boolean] = {
    db.run(variantsV2.filter(_.variantId === id).delete).map(_ > 0)
  }

  override def searchPaginated(
    query: String,
    offset: Int,
    limit: Int,
    mutationType: Option[String] = None
  ): Future[(Seq[VariantV2], Int)] = {
    val upperQuery = query.toUpperCase
    val searchPattern = s"%$upperQuery%"
    val hasQuery = query.trim.nonEmpty

    val typeFilter = mutationType.map(t => s"AND mutation_type = '$t'").getOrElse("")

    val searchSql = if (hasQuery) {
      sql"""
        SELECT * FROM variant_v2
        WHERE (
          UPPER(canonical_name) LIKE $searchPattern
          OR EXISTS (
            SELECT 1 FROM jsonb_array_elements_text(aliases->'common_names') AS name
            WHERE UPPER(name) LIKE $searchPattern
          )
          OR EXISTS (
            SELECT 1 FROM jsonb_array_elements_text(aliases->'rs_ids') AS rsid
            WHERE UPPER(rsid) LIKE $searchPattern
          )
        )
        #$typeFilter
        ORDER BY canonical_name NULLS LAST
        OFFSET $offset LIMIT $limit
      """.as[VariantV2](variantV2GetResult)
    } else {
      sql"""
        SELECT * FROM variant_v2
        WHERE 1=1 #$typeFilter
        ORDER BY canonical_name NULLS LAST
        OFFSET $offset LIMIT $limit
      """.as[VariantV2](variantV2GetResult)
    }

    val countSql = if (hasQuery) {
      sql"""
        SELECT COUNT(*) FROM variant_v2
        WHERE (
          UPPER(canonical_name) LIKE $searchPattern
          OR EXISTS (
            SELECT 1 FROM jsonb_array_elements_text(aliases->'common_names') AS name
            WHERE UPPER(name) LIKE $searchPattern
          )
          OR EXISTS (
            SELECT 1 FROM jsonb_array_elements_text(aliases->'rs_ids') AS rsid
            WHERE UPPER(rsid) LIKE $searchPattern
          )
        )
        #$typeFilter
      """.as[Int].head
    } else {
      sql"""
        SELECT COUNT(*) FROM variant_v2
        WHERE 1=1 #$typeFilter
      """.as[Int].head
    }

    for {
      results <- db.run(searchSql)
      count <- db.run(countSql)
    } yield (results, count)
  }

  override def count(query: Option[String] = None, mutationType: Option[String] = None): Future[Int] = {
    val typeFilter = mutationType.map(t => s"AND mutation_type = '$t'").getOrElse("")

    query match {
      case Some(q) if q.trim.nonEmpty =>
        val upperQuery = q.toUpperCase
        val searchPattern = s"%$upperQuery%"
        db.run(sql"""
          SELECT COUNT(*) FROM variant_v2
          WHERE (
            UPPER(canonical_name) LIKE $searchPattern
            OR EXISTS (
              SELECT 1 FROM jsonb_array_elements_text(aliases->'common_names') AS name
              WHERE UPPER(name) LIKE $searchPattern
            )
          )
          #$typeFilter
        """.as[Int].head)
      case _ =>
        db.run(sql"""SELECT COUNT(*) FROM variant_v2 WHERE 1=1 #$typeFilter""".as[Int].head)
    }
  }

  // === Bulk Operations ===

  override def streamAll(): Future[Seq[VariantV2]] = {
    db.run(variantsV2.result)
  }

  override def findByIds(ids: Seq[Int]): Future[Seq[VariantV2]] = {
    if (ids.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      db.run(variantsV2.filter(_.variantId.inSet(ids)).result)
    }
  }

  // === GetResult for raw SQL queries ===

  private val variantV2GetResult: GetResult[VariantV2] = GetResult { r =>
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
}
