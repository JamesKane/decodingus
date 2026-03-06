package repositories

import jakarta.inject.Inject
import models.dal.domain.genomics.BiosampleVariantCall
import play.api.Logging
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

trait BiosampleVariantCallRepository {
  def findByBiosample(biosampleId: Int): Future[Seq[BiosampleVariantCall]]
  def findByBiosampleAndVariants(biosampleId: Int, variantIds: Seq[Int]): Future[Seq[BiosampleVariantCall]]
  def findByVariant(variantId: Int): Future[Seq[BiosampleVariantCall]]
}

class BiosampleVariantCallRepositoryImpl @Inject()(
  dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with BiosampleVariantCallRepository
    with Logging {

  import models.dal.DatabaseSchema.domain.genomics.biosampleVariantCalls
  import models.dal.MyPostgresProfile.api.*

  override def findByBiosample(biosampleId: Int): Future[Seq[BiosampleVariantCall]] =
    runQuery(biosampleVariantCalls.filter(_.biosampleId === biosampleId).result)

  override def findByBiosampleAndVariants(biosampleId: Int, variantIds: Seq[Int]): Future[Seq[BiosampleVariantCall]] =
    runQuery(biosampleVariantCalls
      .filter(r => r.biosampleId === biosampleId && r.variantId.inSet(variantIds))
      .result)

  override def findByVariant(variantId: Int): Future[Seq[BiosampleVariantCall]] =
    runQuery(biosampleVariantCalls.filter(_.variantId === variantId).result)
}
