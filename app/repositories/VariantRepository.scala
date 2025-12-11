package repositories

import jakarta.inject.Inject
import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api.*
import models.dal.domain.genomics.Variant
import models.domain.genomics.{GenbankContig, VariantGroup, VariantWithContig}
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

/**
 * Trait defining the repository interface for managing genetic variants.
 *
 * This repository provides methods for interacting with a database to perform
 * operations such as retrieving, creating, or finding variants, either individually
 * or in bulk. The operations are asynchronous, returning `Future` results to handle
 * potentially long-running database interactions.
 */
trait VariantRepository {
  /**
   * Finds a genetic variant based on its genomic location and alleles.
   *
   * @param contigId        The ID of the genomic contig (chromosome or sequence) where the variant is located.
   * @param position        The 1-based position of the variant on the specified contig.
   * @param referenceAllele The reference allele (expected allele at the given position).
   * @param alternateAllele The alternate allele (observed allele differing from the reference allele).
   * @return A Future containing an Option of the Variant if found, or None if no matching variant exists.
   */
  def findVariant(
                   contigId: Int,
                   position: Int,
                   referenceAllele: String,
                   alternateAllele: String
                 ): Future[Option[Variant]]

  /**
   * Inserts a new genetic variant into the database.
   *
   * @param variant The variant object containing details such as genomic contig ID, position, reference allele,
   *                alternate allele, type, and optional metadata like rsId or common name.
   * @return A Future containing the ID of the newly inserted variant as an integer.
   */
  def createVariant(variant: Variant): Future[Int]

  /**
   * Creates multiple genetic variants in a single batch operation.
   *
   * @param variants A sequence of Variant objects, each representing a genetic variant 
   *                 with details such as genomic location, reference allele, alternate allele, 
   *                 and optional metadata.
   * @return A Future containing a sequence of integers representing the IDs of the newly created variants.
   */
  def createVariantsBatch(variants: Seq[Variant]): Future[Seq[Int]]

  /**
   * Finds an existing genetic variant in the database by its details or creates a new one if it doesn't exist.
   *
   * @param variant The variant object containing details such as genomic location, reference allele, alternate allele,
   *                variant type, and optional metadata like rsId or common name.
   * @return A Future containing the ID of the found or newly created variant as an integer.
   */
  def findOrCreateVariant(variant: Variant): Future[Int]

  /**
   * Finds or creates a batch of genetic variants. For each variant in the input sequence:
   * - If the variant already exists in the database, its ID is returned.
   * - If the variant does not exist, it is created, and the ID of the newly created variant is returned.
   *
   * @param variants A sequence of Variant objects, each representing a genetic variant
   *                 with details such as genomic location, reference allele, alternate allele,
   *                 and optional metadata.
   * @return A Future containing a sequence of integers, where each integer is the ID of the found
   *         or newly created variant corresponding to the input sequence order.
   */
  def findOrCreateVariantsBatch(variants: Seq[Variant]): Future[Seq[Int]]

  /**
   * Finds or creates variants without creating aliases (for lifted/derived variants).
   */
  def findOrCreateVariantsBatchNoAliases(variants: Seq[Variant]): Future[Seq[Int]]

  /**
   * Finds or creates variants with alias tracking from a specific source.
   */
  def findOrCreateVariantsBatchWithAliases(variants: Seq[Variant], source: String): Future[Seq[Int]]

  /**
   * Searches for variants by name (rsId or commonName).
   *
   * @param name The name to search for.
   * @return A Future containing a sequence of matching Variants.
   */
  def searchByName(name: String): Future[Seq[Variant]]

  // === Curator CRUD Methods ===

  /**
   * Find a variant by ID.
   */
  def findById(id: Int): Future[Option[Variant]]

  /**
   * Find a variant by ID with its associated contig information.
   */
  def findByIdWithContig(id: Int): Future[Option[VariantWithContig]]

  /**
   * Search variants by name with pagination.
   */
  def search(query: String, limit: Int, offset: Int): Future[Seq[Variant]]

  /**
   * Search variants by name with pagination, including contig information.
   */
  def searchWithContig(query: String, limit: Int, offset: Int): Future[Seq[VariantWithContig]]

  /**
   * Count variants matching search criteria.
   */
  def count(query: Option[String]): Future[Int]

  /**
   * Update an existing variant.
   */
  def update(variant: Variant): Future[Boolean]

  /**
   * Delete a variant.
   */
  def delete(id: Int): Future[Boolean]

  // === Variant Grouping Methods ===

  /**
   * Search variants and return them grouped by commonName (primary) or rsId (fallback).
   * Variants with the same group key across different reference builds are grouped together.
   */
  def searchGrouped(query: String, limit: Int): Future[Seq[VariantGroup]]

  /**
   * Get all variants matching a group key (commonName or rsId) with their contig information.
   */
  def getVariantsByGroupKey(groupKey: String): Future[Seq[VariantWithContig]]

  /**
   * Group a sequence of variants (with contig info) by their logical identity.
   */
  def groupVariants(variants: Seq[VariantWithContig]): Seq[VariantGroup]
}

class VariantRepositoryImpl @Inject()(
                                       dbConfigProvider: DatabaseConfigProvider
                                     )(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with VariantRepository {

  import models.dal.DatabaseSchema.domain.genomics.{genbankContigs, variants}

  def findVariant(
                   contigId: Int,
                   position: Int,
                   referenceAllele: String,
                   alternateAllele: String
                 ): Future[Option[Variant]] = {
    val query = variants.filter(v =>
      v.genbankContigId === contigId &&
        v.position === position &&
        v.referenceAllele === referenceAllele &&
        v.alternateAllele === alternateAllele
    ).result.headOption

    db.run(query)
  }

  def searchByName(name: String): Future[Seq[Variant]] = {
    val query = variants.filter(v =>
      v.rsId === name || v.commonName === name
    ).result
    db.run(query)
  }

  def createVariant(variant: Variant): Future[Int] = {
    val insertion = (variants returning variants.map(_.variantId)) += variant
    db.run(insertion)
  }

  def createVariantsBatch(variantBatch: Seq[Variant]): Future[Seq[Int]] = {
    if (variantBatch.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      val insertAction = (variants returning variants.map(_.variantId)) ++= variantBatch
      db.run(insertAction.transactionally)
    }
  }

  def findOrCreateVariant(variant: Variant): Future[Int] = {
    val findExistingQuery = variants
      .filter(v =>
        v.genbankContigId === variant.genbankContigId &&
          v.position === variant.position &&
          v.referenceAllele === variant.referenceAllele &&
          v.alternateAllele === variant.alternateAllele
      )
      .map(_.variantId)
      .result
      .headOption

    val action = findExistingQuery.flatMap {
      case Some(existingId) => DBIO.successful(existingId)
      case None =>
        (variants returning variants.map(_.variantId)) += variant
    }.transactionally

    db.run(action).recoverWith {
      case e: PSQLException if e.getSQLState == "23505" =>
        findVariant(
          variant.genbankContigId,
          variant.position,
          variant.referenceAllele,
          variant.alternateAllele
        ).flatMap {
          case Some(v) => Future.successful(v.variantId.get)
          case None => Future.failed(e)
        }
    }
  }

  def findOrCreateVariantsBatch(batch: Seq[Variant]): Future[Seq[Int]] = {
    findOrCreateVariantsBatchWithAliases(batch, "ybrowse")
  }

  /**
   * Find or create variants without creating aliases (for lifted/derived variants).
   */
  def findOrCreateVariantsBatchNoAliases(batch: Seq[Variant]): Future[Seq[Int]] = {
    if (batch.isEmpty) return Future.successful(Seq.empty)

    val upsertActions = batch.map { variant =>
      sql"""
        INSERT INTO variant (
          genbank_contig_id, position, reference_allele, alternate_allele,
          variant_type, rs_id, common_name
        ) VALUES (
          ${variant.genbankContigId}, ${variant.position},
          ${variant.referenceAllele}, ${variant.alternateAllele},
          ${variant.variantType}, ${variant.rsId}, ${variant.commonName}
        )
        ON CONFLICT (genbank_contig_id, position, reference_allele, alternate_allele)
        DO UPDATE SET
          variant_type = EXCLUDED.variant_type,
          rs_id = COALESCE(EXCLUDED.rs_id, variant.rs_id),
          common_name = COALESCE(EXCLUDED.common_name, variant.common_name)
        RETURNING variant_id
      """.as[Int].head
    }

    runTransactionally(DBIO.sequence(upsertActions))
  }

  /**
   * Find or create variants in batch, recording incoming names as aliases.
   *
   * When a variant already exists (matched by position/alleles), incoming names
   * that differ from existing names are recorded as aliases. This preserves
   * alternative nomenclature from different sources (YBrowse, ISOGG, publications, etc.).
   *
   * Comma-separated names (e.g., "BY11122,FGC49371") are split into individual aliases.
   *
   * @param batch  Variants to upsert
   * @param source Source identifier for alias tracking (e.g., "ybrowse", "isogg", "curator")
   * @return Sequence of variant IDs (existing or newly created)
   */
  def findOrCreateVariantsBatchWithAliases(batch: Seq[Variant], source: String): Future[Seq[Int]] = {
    if (batch.isEmpty) return Future.successful(Seq.empty)

    // For the variant record, use the first name if comma-separated
    val upsertActions = batch.map { variant =>
      val primaryName = variant.commonName.map(_.split(",").head.trim)
      sql"""
        INSERT INTO variant (
          genbank_contig_id, position, reference_allele, alternate_allele,
          variant_type, rs_id, common_name
        ) VALUES (
          ${variant.genbankContigId}, ${variant.position},
          ${variant.referenceAllele}, ${variant.alternateAllele},
          ${variant.variantType}, ${variant.rsId}, $primaryName
        )
        ON CONFLICT (genbank_contig_id, position, reference_allele, alternate_allele)
        DO UPDATE SET
          variant_type = EXCLUDED.variant_type,
          rs_id = COALESCE(EXCLUDED.rs_id, variant.rs_id),
          common_name = COALESCE(EXCLUDED.common_name, variant.common_name)
        RETURNING variant_id
      """.as[Int].head
    }

    // Execute upserts to get variant IDs
    val upsertResult = runTransactionally(DBIO.sequence(upsertActions))

    // After getting IDs, add aliases for any incoming names (split comma-separated)
    upsertResult.flatMap { variantIds =>
      val aliasInserts = batch.zip(variantIds).flatMap { case (variant, variantId) =>
        // Split comma-separated common names into individual aliases
        val commonNameAliases = variant.commonName.toSeq.flatMap { names =>
          names.split(",").map(_.trim).filter(_.nonEmpty).map { name =>
            (variantId, "common_name", name)
          }
        }

        val rsIdAliases = variant.rsId.toSeq.map(id => (variantId, "rs_id", id))

        (commonNameAliases ++ rsIdAliases).map { case (vid, aliasType, aliasValue) =>
          sql"""
            INSERT INTO variant_alias (variant_id, alias_type, alias_value, source, is_primary, created_at)
            VALUES ($vid, $aliasType, $aliasValue, $source, FALSE, NOW())
            ON CONFLICT (variant_id, alias_type, alias_value) DO NOTHING
          """.asUpdate
        }
      }

      if (aliasInserts.isEmpty) {
        Future.successful(variantIds)
      } else {
        db.run(DBIO.sequence(aliasInserts)).map(_ => variantIds)
      }
    }
  }

  // === Curator CRUD Methods Implementation ===

  override def findById(id: Int): Future[Option[Variant]] = {
    db.run(variants.filter(_.variantId === id).result.headOption)
  }

  override def findByIdWithContig(id: Int): Future[Option[VariantWithContig]] = {
    val query = for {
      v <- variants if v.variantId === id
      c <- genbankContigs if c.genbankContigId === v.genbankContigId
    } yield (v, c)

    db.run(query.result.headOption).map(_.map { case (v, c) => VariantWithContig(v, c) })
  }

  override def search(query: String, limit: Int, offset: Int): Future[Seq[Variant]] = {
    val upperQuery = query.toUpperCase
    val searchQuery = variants.filter(v =>
      v.rsId.toUpperCase.like(s"%$upperQuery%") ||
        v.commonName.toUpperCase.like(s"%$upperQuery%")
    )
      .sortBy(v => (v.commonName, v.rsId))
      .drop(offset)
      .take(limit)
      .result

    db.run(searchQuery)
  }

  override def searchWithContig(query: String, limit: Int, offset: Int): Future[Seq[VariantWithContig]] = {
    val upperQuery = query.toUpperCase
    val searchQuery = (for {
      v <- variants if v.rsId.toUpperCase.like(s"%$upperQuery%") || v.commonName.toUpperCase.like(s"%$upperQuery%")
      c <- genbankContigs if c.genbankContigId === v.genbankContigId
    } yield (v, c))
      .sortBy { case (v, _) => (v.commonName, v.rsId) }
      .drop(offset)
      .take(limit)
      .result

    db.run(searchQuery).map(_.map { case (v, c) => VariantWithContig(v, c) })
  }

  override def count(query: Option[String]): Future[Int] = {
    val baseQuery = query match {
      case Some(q) =>
        val upperQuery = q.toUpperCase
        variants.filter(v =>
          v.rsId.toUpperCase.like(s"%$upperQuery%") ||
            v.commonName.toUpperCase.like(s"%$upperQuery%")
        )
      case None => variants
    }
    db.run(baseQuery.length.result)
  }

  override def update(variant: Variant): Future[Boolean] = {
    variant.variantId match {
      case Some(id) =>
        db.run(
          variants
            .filter(_.variantId === id)
            .map(v => (v.variantType, v.rsId, v.commonName))
            .update((variant.variantType, variant.rsId, variant.commonName))
        ).map(_ > 0)
      case None => Future.successful(false)
    }
  }

  override def delete(id: Int): Future[Boolean] = {
    db.run(variants.filter(_.variantId === id).delete).map(_ > 0)
  }

  // === Variant Grouping Methods Implementation ===

  override def searchGrouped(query: String, limit: Int): Future[Seq[VariantGroup]] = {
    val upperQuery = query.toUpperCase

    // Search variants by name fields OR by aliases
    // Use raw SQL for the alias join to keep it efficient
    val searchQuery = sql"""
      SELECT DISTINCT v.variant_id, v.genbank_contig_id, v.position, v.reference_allele,
             v.alternate_allele, v.variant_type, v.rs_id, v.common_name,
             c.genbank_contig_id as c_id, c.accession, c.common_name as c_common_name,
             c.reference_genome, COALESCE(c.seq_length, 0) as seq_len
      FROM variant v
      JOIN genbank_contig c ON c.genbank_contig_id = v.genbank_contig_id
      LEFT JOIN variant_alias va ON va.variant_id = v.variant_id
      WHERE UPPER(v.rs_id) LIKE ${s"%$upperQuery%"}
         OR UPPER(v.common_name) LIKE ${s"%$upperQuery%"}
         OR UPPER(va.alias_value) LIKE ${s"%$upperQuery%"}
      ORDER BY v.common_name, v.rs_id
    """.as[(Int, Int, Int, String, String, String, Option[String], Option[String],
            Int, String, Option[String], Option[String], Int)]

    db.run(searchQuery).map { results =>
      val variantsWithContig = results.map { case (vid, contigId, pos, ref, alt, vtype, rsId, commonName,
                                                    cId, accession, cCommonName, refGenome, seqLen) =>
        val variant = Variant(Some(vid), contigId, pos, ref, alt, vtype, rsId, commonName)
        val contig = GenbankContig(Some(cId), accession, cCommonName, refGenome, seqLen)
        VariantWithContig(variant, contig)
      }
      VariantGroup.fromVariants(variantsWithContig).take(limit)
    }
  }

  override def getVariantsByGroupKey(groupKey: String): Future[Seq[VariantWithContig]] = {
    val searchQuery = (for {
      v <- variants if v.commonName === groupKey || v.rsId === groupKey
      c <- genbankContigs if c.genbankContigId === v.genbankContigId
    } yield (v, c))
      .result

    db.run(searchQuery).map(_.map { case (v, c) => VariantWithContig(v, c) })
  }

  override def groupVariants(variants: Seq[VariantWithContig]): Seq[VariantGroup] = {
    VariantGroup.fromVariants(variants)
  }
}
