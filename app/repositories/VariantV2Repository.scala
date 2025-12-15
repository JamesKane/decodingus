package repositories

import jakarta.inject.Inject
import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api.*
import models.dal.domain.genomics.*
import models.domain.genomics.{MutationType, NamingStatus, VariantV2}
import org.postgresql.util.PSQLException
import play.api.Logging
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

  def findById(id: Int): Future[Option[VariantV2]]
  def findByCanonicalName(name: String, definingHaplogroupId: Option[Int] = None): Future[Option[VariantV2]]
  def findAllByCanonicalName(name: String): Future[Seq[VariantV2]]

  // === JSONB Alias Search ===

  def findByAlias(aliasValue: String): Future[Seq[VariantV2]]
  def searchByName(query: String): Future[Seq[VariantV2]]

  // === JSONB Coordinate Search ===

  def findByCoordinates(
    refGenome: String,
    contig: String,
    position: Int,
    ref: String,
    alt: String
  ): Future[Option[VariantV2]]

  def findByPositionRange(
    refGenome: String,
    contig: String,
    startPosition: Int,
    endPosition: Int
  ): Future[Seq[VariantV2]]

  // === Upsert Operations ===

  def create(variant: VariantV2): Future[Int]
  def createBatch(variants: Seq[VariantV2]): Future[Seq[Int]]

  /**
   * Perform a batch upsert (INSERT or UPDATE) for a sequence of variants.
   * Matches on either canonical name + defining haplogroup (for named variants)
   * or hs1 coordinates (for unnamed variants).
   */
  def upsertBatch(variants: Seq[VariantV2]): Future[Seq[Int]]

  /**
   * Bulk update the `annotations` column by finding overlapping regions and STRs.
   * This is a heavy operation intended for background jobs.
   */
  def updateRegionAnnotations(): Future[Int]

  // === JSONB Update Operations ===

  def addCoordinates(variantId: Int, refGenome: String, coordinates: JsObject): Future[Boolean]
  def addAlias(variantId: Int, aliasType: String, aliasValue: String, source: Option[String] = None): Future[Boolean]

  // === Alias Source Management ===

  def bulkUpdateAliasSource(aliasPrefix: String, newSource: String, oldSource: Option[String]): Future[Int]
  def getAliasSourceStats(): Future[Seq[(String, Int)]]
  def countAliasesByPrefixAndSource(aliasPrefix: String, source: Option[String]): Future[Int]
  def updateEvidence(variantId: Int, evidence: JsObject): Future[Boolean]

  // === Curator CRUD ===

  def update(variant: VariantV2): Future[Boolean]
  def updateBatch(variants: Seq[VariantV2]): Future[Int]
  def delete(id: Int): Future[Boolean]
  def searchPaginated(
    query: String,
    offset: Int,
    limit: Int,
    mutationType: Option[String] = None
  ): Future[(Seq[VariantV2], Int)]
  def count(query: Option[String] = None, mutationType: Option[String] = None): Future[Int]

  // === Bulk Operations ===

  def streamAll(): Future[Seq[VariantV2]]
  def findByIds(ids: Seq[Int]): Future[Seq[VariantV2]]

  // === DU Naming Authority ===

  def nextDuName(): Future[String]
  def currentDuName(): Future[Option[String]]
  def isDuName(name: String): Boolean
  def createWithDuName(variant: VariantV2): Future[VariantV2]
}

class VariantV2RepositoryImpl @Inject()(
  dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with VariantV2Repository with Logging {

  import slick.ast.BaseTypedType
  import slick.jdbc.JdbcType

  private val variantsV2 = TableQuery[VariantV2Table]
  
  implicit val mutationTypeMapper: JdbcType[MutationType] with BaseTypedType[MutationType] =
    MappedColumnType.base[MutationType, String](
      _.dbValue,
      MutationType.fromStringOrDefault(_)
    )

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

  override def upsertBatch(variants: Seq[VariantV2]): Future[Seq[Int]] = {
    if (variants.isEmpty) return Future.successful(Seq.empty)

    val (namedVariantsRaw, unnamedVariantsRaw) = variants.partition(_.canonicalName.isDefined)

    // Deduplicate named variants by conflict key
    val namedVariants = namedVariantsRaw
      .groupBy(v => (v.canonicalName, v.definingHaplogroupId))
      .values.map(_.head).toSeq

    // Deduplicate unnamed variants by conflict key
    val unnamedVariants = unnamedVariantsRaw
      .groupBy(v => v.getCoordinates("hs1").toString)
      .values.map(_.head).toSeq

    def toJsonb(jsValue: play.api.libs.json.JsValue): String = Json.stringify(jsValue)
    def optString(s: Option[String]): String = s.map(v => s"'$v'").getOrElse("NULL")
    def optInt(i: Option[Int]): String = i.map(_.toString).getOrElse("NULL")

    // === Named Variants Upsert ===
    val namedUpsertAction = if (namedVariants.nonEmpty) {
      val namedValues = namedVariants.map { v =>
        val canonicalName = v.canonicalName.getOrElse(throw new IllegalArgumentException("Named variant must have a canonical name"))
        val definingHaplogroupId = optInt(v.definingHaplogroupId)
        val mutationType = v.mutationType.dbValue
        val namingStatus = v.namingStatus.dbValue
        val aliases = toJsonb(v.aliases)
        val coordinates = toJsonb(v.coordinates)
        val evidence = toJsonb(v.evidence)
        val primers = toJsonb(v.primers)
        val notes = optString(v.notes)
        val annotations = toJsonb(v.annotations)
        val createdAt = v.createdAt.getEpochSecond
        val updatedAt = v.updatedAt.getEpochSecond
        
        s"(NEXTVAL('variant_v2_variant_id_seq'), '$canonicalName', '$mutationType', '$namingStatus', '$aliases', '$coordinates', $definingHaplogroupId, '$evidence', '$primers', $notes, '$annotations', TO_TIMESTAMP($createdAt), TO_TIMESTAMP($updatedAt))"
      }.mkString(",")

      sql"""
        INSERT INTO variant_v2 (variant_id, canonical_name, mutation_type, naming_status, aliases, coordinates, defining_haplogroup_id, evidence, primers, notes, annotations, created_at, updated_at)
        VALUES #$namedValues
        ON CONFLICT (canonical_name, COALESCE(defining_haplogroup_id, -1)) WHERE canonical_name IS NOT NULL DO UPDATE SET
          mutation_type = EXCLUDED.mutation_type,
          aliases = variant_v2.aliases || EXCLUDED.aliases,
          coordinates = variant_v2.coordinates || EXCLUDED.coordinates,
          evidence = variant_v2.evidence || EXCLUDED.evidence,
          primers = variant_v2.primers || EXCLUDED.primers,
          annotations = variant_v2.annotations || EXCLUDED.annotations,
          notes = COALESCE(variant_v2.notes, EXCLUDED.notes),
          naming_status = CASE
                            WHEN variant_v2.naming_status = 'UNNAMED' AND EXCLUDED.naming_status = 'NAMED' THEN 'NAMED'
                            ELSE variant_v2.naming_status
                          END,
          updated_at = NOW()
        RETURNING variant_id
      """.as[Int]
    } else DBIO.successful(Seq.empty[Int])

    // === Unnamed Variants Upsert ===
    val unnamedUpsertAction = if (unnamedVariants.nonEmpty) {
      val unnamedValues = unnamedVariants.map { v =>
        val hs1CoordsOpt = v.getCoordinates("hs1")
        val (contig, position, ref, alt) = hs1CoordsOpt match {
          case Some(c) =>
            ((c \ "contig").asOpt[String].getOrElse(""), (c \ "position").asOpt[Int].getOrElse(0).toString, (c \ "ref").asOpt[String].getOrElse(""), (c \ "alt").asOpt[String].getOrElse(""))
          case None => throw new IllegalArgumentException("Unnamed variant without hs1 coordinates cannot be upserted.")
        }
        
        val mutationType = v.mutationType.dbValue
        val namingStatus = v.namingStatus.dbValue
        val aliases = toJsonb(v.aliases)
        val coordinates = toJsonb(v.coordinates)
        val evidence = toJsonb(v.evidence)
        val primers = toJsonb(v.primers)
        val notes = optString(v.notes)
        val annotations = toJsonb(v.annotations)
        val createdAt = v.createdAt.getEpochSecond
        val updatedAt = v.updatedAt.getEpochSecond

        s"(NEXTVAL('variant_v2_variant_id_seq'), NULL, '$mutationType', '$namingStatus', '$aliases', '$coordinates', NULL, '$evidence', '$primers', $notes, '$annotations', TO_TIMESTAMP($createdAt), TO_TIMESTAMP($updatedAt))"
      }.mkString(",")

      sql"""
        INSERT INTO variant_v2 (variant_id, canonical_name, mutation_type, naming_status, aliases, coordinates, defining_haplogroup_id, evidence, primers, notes, annotations, created_at, updated_at)
        VALUES #$unnamedValues
        ON CONFLICT (
          (coordinates->'hs1'->>'contig'),
          ((coordinates->'hs1'->>'position')::int),
          (coordinates->'hs1'->>'ref'),
          (coordinates->'hs1'->>'alt')
        ) WHERE canonical_name IS NULL DO UPDATE SET
          mutation_type = EXCLUDED.mutation_type,
          aliases = variant_v2.aliases || EXCLUDED.aliases,
          coordinates = variant_v2.coordinates || EXCLUDED.coordinates,
          evidence = variant_v2.evidence || EXCLUDED.evidence,
          primers = variant_v2.primers || EXCLUDED.primERS,
          annotations = variant_v2.annotations || EXCLUDED.annotations,
          notes = COALESCE(variant_v2.notes, EXCLUDED.notes),
          naming_status = EXCLUDED.naming_status,
          updated_at = NOW()
        RETURNING variant_id
      """.as[Int]
    } else DBIO.successful(Seq.empty[Int])

    db.run(
      DBIO.sequence(Seq(namedUpsertAction, unnamedUpsertAction)).map(_.flatten).transactionally
    )
  }

  override def updateRegionAnnotations(): Future[Int] = {
    // Updates annotations with region and STR overlaps
    val query = sqlu"""
      WITH region_overlaps AS (
        SELECT v.variant_id, jsonb_agg(
          jsonb_build_object('type', r.region_type, 'name', r.name)
        ) as region_list
        FROM variant_v2 v
        JOIN genome_region_v2 r ON (
          v.coordinates->'GRCh38'->>'contig' = r.coordinates->'GRCh38'->>'contig' AND
          (v.coordinates->'GRCh38'->>'position')::int >= (r.coordinates->'GRCh38'->>'start')::int AND
          (v.coordinates->'GRCh38'->>'position')::int <= (r.coordinates->'GRCh38'->>'end')::int
        )
        GROUP BY v.variant_id
      ),
      str_overlaps AS (
        SELECT v.variant_id, jsonb_agg(
          jsonb_build_object(
            'name', s.canonical_name, 
            'motif', s.coordinates->'GRCh38'->>'repeatMotif',
            'period', (s.coordinates->'GRCh38'->>'period')::int
          )
        ) as str_list
        FROM variant_v2 v
        JOIN variant_v2 s ON (
          s.mutation_type = 'STR' AND
          v.mutation_type != 'STR' AND
          v.coordinates->'GRCh38'->>'contig' = s.coordinates->'GRCh38'->>'contig' AND
          (v.coordinates->'GRCh38'->>'position')::int >= (s.coordinates->'GRCh38'->>'start')::int AND
          (v.coordinates->'GRCh38'->>'position')::int <= (s.coordinates->'GRCh38'->>'end')::int
        )
        GROUP BY v.variant_id
      )
      UPDATE variant_v2 v
      SET annotations = 
          jsonb_build_object(
            'regions', COALESCE(ro.region_list, '[]'::jsonb),
            'strs', COALESCE(so.str_list, '[]'::jsonb)
          ),
          updated_at = NOW()
      FROM region_overlaps ro
      LEFT JOIN str_overlaps so ON ro.variant_id = so.variant_id
      WHERE v.variant_id = ro.variant_id
        AND (ro.region_list IS NOT NULL OR so.str_list IS NOT NULL)
    """
    db.run(query)
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
    val oldSourceFilter = oldSource.map(s => s"AND aliases->'sources' ? '$s'").getOrElse("")
    val upperPrefix = aliasPrefix.toUpperCase
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
              v.annotations,
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
              variant.annotations,
              now
            ))
        ).map(_ > 0)
      case None => Future.successful(false)
    }
  }

  override def updateBatch(variants: Seq[VariantV2]): Future[Int] = {
    if (variants.isEmpty) return Future.successful(0)
    val actions = DBIO.sequence(variants.flatMap { variant =>
      variant.variantId.map { id =>
        val now = Instant.now()
        variantsV2.filter(_.variantId === id).map(v => (v.canonicalName, v.mutationType, v.namingStatus, v.aliases, v.coordinates, v.definingHaplogroupId, v.evidence, v.primers, v.notes, v.annotations, v.updatedAt)).update((variant.canonicalName, variant.mutationType, variant.namingStatus, variant.aliases, variant.coordinates, variant.definingHaplogroupId, variant.evidence, variant.primers, variant.notes, variant.annotations, now))
      }
    })
    db.run(actions.transactionally).map(_.sum)
  }

  override def delete(id: Int): Future[Boolean] = {
    db.run(variantsV2.filter(_.variantId === id).delete).map(_ > 0)
  }

  override def searchPaginated(query: String, offset: Int, limit: Int, mutationType: Option[String] = None): Future[(Seq[VariantV2], Int)] = {
    val upperQuery = query.toUpperCase
    val searchPattern = s"%$upperQuery%"
    val hasQuery = query.trim.nonEmpty
    val typeFilter = mutationType.map(t => s"AND mutation_type = '$t'").getOrElse("")

    val searchSql = if (hasQuery) {
      sql"""
        SELECT * FROM variant_v2
        WHERE (UPPER(canonical_name) LIKE $searchPattern OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(aliases->'common_names') AS name WHERE UPPER(name) LIKE $searchPattern) OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(aliases->'rs_ids') AS rsid WHERE UPPER(rsid) LIKE $searchPattern))
        #$typeFilter
        ORDER BY canonical_name NULLS LAST OFFSET $offset LIMIT $limit
      """.as[VariantV2](variantV2GetResult)
    } else {
      sql"""SELECT * FROM variant_v2 WHERE 1=1 #$typeFilter ORDER BY canonical_name NULLS LAST OFFSET $offset LIMIT $limit""".as[VariantV2](variantV2GetResult)
    }

    val countSql = if (hasQuery) {
      sql"""
        SELECT COUNT(*) FROM variant_v2
        WHERE (UPPER(canonical_name) LIKE $searchPattern OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(aliases->'common_names') AS name WHERE UPPER(name) LIKE $searchPattern) OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(aliases->'rs_ids') AS rsid WHERE UPPER(rsid) LIKE $searchPattern))
        #$typeFilter
      """.as[Int].head
    } else {
      sql"""SELECT COUNT(*) FROM variant_v2 WHERE 1=1 #$typeFilter""".as[Int].head
    }

    for { results <- db.run(searchSql); count <- db.run(countSql) } yield (results, count)
  }

  override def count(query: Option[String] = None, mutationType: Option[String] = None): Future[Int] = {
    val typeFilter = mutationType.map(t => s"AND mutation_type = '$t'").getOrElse("")
    query match {
      case Some(q) if q.trim.nonEmpty =>
        val upperQuery = q.toUpperCase
        val searchPattern = s"%$upperQuery%"
        db.run(sql"""
          SELECT COUNT(*) FROM variant_v2
          WHERE (UPPER(canonical_name) LIKE $searchPattern OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(aliases->'common_names') AS name WHERE UPPER(name) LIKE $searchPattern))
          #$typeFilter
        """.as[Int].head)
      case _ =>
        db.run(sql"""SELECT COUNT(*) FROM variant_v2 WHERE 1=1 #$typeFilter""".as[Int].head)
    }
  }

  // === Bulk Operations ===

  override def streamAll(): Future[Seq[VariantV2]] = db.run(variantsV2.result)
  override def findByIds(ids: Seq[Int]): Future[Seq[VariantV2]] = if (ids.isEmpty) Future.successful(Seq.empty) else db.run(variantsV2.filter(_.variantId.inSet(ids)).result)

  // === DU Naming Authority ===

  private val DuNamePattern = "^DU[1-9][0-9]*$".r
  override def nextDuName(): Future[String] = db.run(sql"SELECT next_du_name()".as[String].head)
  override def currentDuName(): Future[Option[String]] = db.run(sql"SELECT current_du_name()".as[String].headOption).recover { case _: PSQLException => None }
  override def isDuName(name: String): Boolean = DuNamePattern.matches(name)
  override def createWithDuName(variant: VariantV2): Future[VariantV2] = {
    val action = for {
      duName <- sql"SELECT next_du_name()".as[String].head
      now = Instant.now()
      id <- (variantsV2 returning variantsV2.map(_.variantId)) += variant.copy(canonicalName = Some(duName), namingStatus = NamingStatus.Named, createdAt = now, updatedAt = now)
    } yield variant.copy(variantId = Some(id), canonicalName = Some(duName), namingStatus = NamingStatus.Named)
    db.run(action.transactionally)
  }

  // === GetResult for raw SQL queries ===

  private val variantV2GetResult: GetResult[VariantV2] = GetResult { r =>
    val variantId = r.nextIntOption() // 1
    val canonicalName = r.nextStringOption() // 2
    val mutationTypeStr = r.nextString() // 3
    val namingStatusStr = r.nextString() // 4
    val aliasesStr = r.nextString() // 5
    val coordinatesStr = r.nextString() // 6
    val definingHaplogroupId = r.nextIntOption() // 7
    val evidenceStr = r.nextString() // 8
    val primersStr = r.nextString() // 9
    val notes = r.nextStringOption() // 10
    val createdAt = r.nextTimestamp().toInstant // 11
    val updatedAt = r.nextTimestamp().toInstant // 12
    val annotationsStr = r.nextString() // 13
    
    VariantV2(
      variantId = variantId,
      canonicalName = canonicalName,
      mutationType = MutationType.fromStringOrDefault(mutationTypeStr),
      namingStatus = NamingStatus.fromStringOrDefault(namingStatusStr),
      aliases = Json.parse(aliasesStr),
      coordinates = Json.parse(coordinatesStr),
      definingHaplogroupId = definingHaplogroupId,
      evidence = Json.parse(evidenceStr),
      primers = Json.parse(primersStr),
      notes = notes,
      annotations = Json.parse(annotationsStr),
      createdAt = createdAt,
      updatedAt = updatedAt
    )
  }
}
