package repositories

import jakarta.inject.Inject
import models.Variant
import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api.*
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
}