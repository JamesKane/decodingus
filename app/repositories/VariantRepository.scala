package repositories

import jakarta.inject.Inject
import models.Variant
import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api.*
import org.postgresql.util.PSQLException
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

/**
 * A repository that provides methods for managing and querying genetic variant data.
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

  def createVariantsBatch(variants: Seq[Variant]): Future[Seq[Int]]

  def findOrCreateVariant(variant: Variant): Future[Int]

  def findOrCreateVariantsBatch(variants: Seq[Variant]): Future[Seq[Int]]
}

class VariantRepositoryImpl @Inject()(
                                       dbConfigProvider: DatabaseConfigProvider
                                     )(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with VariantRepository {

  import models.dal.DatabaseSchema.domain.variants

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
    // Create a sequence of individual upsert actions
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
      """.as[Int].head // Use .head to get a single Int instead of Vector[Int]
    }

    // Combine all actions into a single DBIO action
    val combinedAction = DBIO.sequence(upsertActions)

    // Use runTransactionally from BaseRepository
    runTransactionally(combinedAction)
  }
}
