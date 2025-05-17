package repositories

import jakarta.inject.Inject
import models.*
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
  def findVariants(query: String): Future[Seq[Variant]]

  /**
   * Retrieves the list of variants associated with a given haplogroup.
   *
   * @param haplogroupId the identifier of the haplogroup for which variants are to be retrieved
   * @return a future containing a sequence of tuples, where each tuple consists of a Variant and its associated GenbankContig
   */
  def getHaplogroupVariants(haplogroupId: Int): Future[Seq[(Variant, GenbankContig)]]

  /**
   * Retrieves a list of genetic variants associated with the given haplogroup.
   *
   * @param haplogroupId The unique identifier of the haplogroup for which the variants are being requested.
   * @return A Future containing a sequence of Variant objects associated with the specified haplogroup.
   */
  def getVariantsByHaplogroup(haplogroupId: Int): Future[Seq[Variant]]

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
}

class HaplogroupVariantRepositoryImpl @Inject()(
                                                 dbConfigProvider: DatabaseConfigProvider
                                               )(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with HaplogroupVariantRepository {

  import models.dal.DatabaseSchema.*
  import models.dal.MyPostgresProfile.api.*

  override def findVariants(query: String): Future[Seq[Variant]] = {
    val normalizedQuery = query.trim.toLowerCase

    def buildQuery = {
      if (normalizedQuery.startsWith("rs")) {
        variants.filter(v => v.rsId.isDefined && v.rsId === normalizedQuery)
      } else if (normalizedQuery.contains(":")) {
        val parts = normalizedQuery.split(":")
        parts.length match {
          case 2 =>
            for {
              variant <- variants
              contig <- genbankContigs if variant.genbankContigId === contig.genbankContigId
              if (variant.commonName.isDefined && variant.commonName === parts(0)) ||
                (contig.commonName.isDefined && contig.commonName === parts(0))
              if variant.position === parts(1).toIntOption.getOrElse(0)
            } yield variant
          case 4 =>
            for {
              variant <- variants
              contig <- genbankContigs if variant.genbankContigId === contig.genbankContigId
              if (variant.commonName.isDefined && variant.commonName === parts(0)) ||
                (contig.commonName.isDefined && contig.commonName === parts(0))
              if variant.position === parts(1).toIntOption.getOrElse(0) &&
                variant.referenceAllele === parts(2) &&
                variant.alternateAllele === parts(3)
            } yield variant
          case _ =>
            variants.filter(_ => false)
        }
      } else {
        variants.filter(v =>
          (v.rsId.isDefined && v.rsId === normalizedQuery) ||
            (v.commonName.isDefined && v.commonName === normalizedQuery)
        )
      }
    }

    runQuery(buildQuery.result)
  }

  override def getHaplogroupVariants(haplogroupId: Int): Future[Seq[(Variant, GenbankContig)]] = {
    val query = for {
      hv <- haplogroupVariants if hv.haplogroupId === haplogroupId
      v <- variants if v.variantId === hv.variantId
      gc <- genbankContigs if gc.genbankContigId === v.genbankContigId
    } yield (v, gc)

    runQuery(query.result)
  }

  override def getVariantsByHaplogroup(haplogroupId: Int): Future[Seq[Variant]] = {
    val query = for {
      hv <- haplogroupVariants if hv.haplogroupId === haplogroupId
      variant <- variants if variant.variantId === hv.variantId
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
    val insertion = haplogroupVariants += HaplogroupVariant(None, haplogroupId, variantId)
    runQuery(insertion)
  }

  def removeVariantFromHaplogroup(haplogroupId: Int, variantId: Int): Future[Int] = {
    val query = haplogroupVariants
      .filter(hv => hv.haplogroupId === haplogroupId && hv.variantId === variantId)
      .delete

    runQuery(query)
  }

  override def findHaplogroupsByDefiningVariant(variantId: String, haplogroupType: HaplogroupType): Future[Seq[Haplogroup]] = {
    val query = for {
      variant <- variants if variant.rsId === variantId || variant.variantId === variantId.toIntOption
      haplogroupVariant <- haplogroupVariants if haplogroupVariant.variantId === variant.variantId
      haplogroup <- haplogroups if
        haplogroup.haplogroupId === haplogroupVariant.haplogroupId &&
          haplogroup.haplogroupType === haplogroupType.toString
    } yield haplogroup

    runQuery(query.result)
  }
}