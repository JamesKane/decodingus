package repositories

import jakarta.inject.Inject
import models.Variant
import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api.*
import org.postgresql.util.PSQLException
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}

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

  def findOrCreateVariant(variant: Variant): Future[Int]
}

class VariantRepositoryImpl @Inject()(
                                       protected val dbConfigProvider: DatabaseConfigProvider
                                     )(implicit ec: ExecutionContext)
  extends VariantRepository
    with HasDatabaseConfigProvider[MyPostgresProfile] {

  import models.dal.DatabaseSchema.variants

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

  def findOrCreateVariant(variant: Variant): Future[Int] = {
    // Use database-level upsert to handle race conditions
    (for {
      existingVariant <- findVariant(
        variant.genbankContigId,
        variant.position,
        variant.referenceAllele,
        variant.alternateAllele
      )
      id <- existingVariant match {
        case Some(v) => Future.successful(v.variantId.get)
        case None => createVariantWithConflictHandling(variant)
      }
    } yield id).recoverWith {
      case e: PSQLException if e.getSQLState == "23505" => // Unique violation
        // If we hit a conflict, try to find the variant again
        findVariant(
          variant.genbankContigId,
          variant.position,
          variant.referenceAllele,
          variant.alternateAllele
        ).flatMap {
          case Some(v) => Future.successful(v.variantId.get)
          case None => Future.failed(e) // Something unexpected happened
        }
    }
  }

  private def createVariantWithConflictHandling(variant: Variant): Future[Int] = {
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
      case Some(existingId) =>
        // Variant exists, return its ID
        DBIO.successful(existingId)
      case None =>
        // Variant doesn't exist, try to insert it
        (variants returning variants.map(_.variantId)) += variant
    }.transactionally

    db.run(action).recoverWith {
      case e: PSQLException if e.getSQLState == "23505" =>
        // If we hit a conflict, try to find the variant again
        db.run(findExistingQuery).flatMap {
          case Some(existingId) => Future.successful(existingId)
          case None => Future.failed(e) // Something unexpected happened
        }
    }
  }

}