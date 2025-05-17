package repositories

import jakarta.inject.Inject
import models.Variant
import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api.*
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}

import scala.concurrent.{ExecutionContext, Future}

trait VariantRepository {
  def findVariant(
                   contigId: Int,
                   position: Int,
                   referenceAllele: String,
                   alternateAllele: String
                 ): Future[Option[Variant]]

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