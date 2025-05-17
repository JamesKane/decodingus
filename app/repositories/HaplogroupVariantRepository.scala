package repositories

import jakarta.inject.Inject
import models.*
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

trait HaplogroupVariantRepository {
  def findVariants(query: String): Future[Seq[Variant]]

  def getHaplogroupVariants(haplogroupId: Int): Future[Seq[(Variant, GenbankContig)]]

  def getVariantsByHaplogroup(haplogroupId: Int): Future[Seq[Variant]]

  def getHaplogroupsByVariant(variantId: Int): Future[Seq[Haplogroup]]

  def addVariantToHaplogroup(haplogroupId: Int, variantId: Int): Future[Int]

  def removeVariantFromHaplogroup(haplogroupId: Int, variantId: Int): Future[Int]

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