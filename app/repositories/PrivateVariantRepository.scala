package repositories

import jakarta.inject.Inject
import models.HaplogroupType
import models.domain.discovery.*
import play.api.Logging
import play.api.db.slick.DatabaseConfigProvider

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

trait PrivateVariantRepository {
  def create(pv: BiosamplePrivateVariant): Future[BiosamplePrivateVariant]
  def createAll(pvs: Seq[BiosamplePrivateVariant]): Future[Seq[BiosamplePrivateVariant]]
  def findBySample(sampleType: BiosampleSourceType, sampleId: Int): Future[Seq[BiosamplePrivateVariant]]
  def findBySampleGuid(sampleGuid: UUID): Future[Seq[BiosamplePrivateVariant]]
  def findByVariantId(variantId: Int): Future[Seq[BiosamplePrivateVariant]]
  def findByTerminalHaplogroup(haplogroupId: Int): Future[Seq[BiosamplePrivateVariant]]
  def findActiveByVariantIds(variantIds: Set[Int], haplogroupType: HaplogroupType): Future[Seq[BiosamplePrivateVariant]]
  def updateStatus(id: Int, status: PrivateVariantStatus): Future[Boolean]
  def countByVariant(variantId: Int, haplogroupType: HaplogroupType): Future[Int]
}

class PrivateVariantRepositoryImpl @Inject()(
  dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with PrivateVariantRepository
    with Logging {

  import models.dal.DatabaseSchema.domain.haplogroups.biosamplePrivateVariants
  import models.dal.MyPostgresProfile.api.*

  override def create(pv: BiosamplePrivateVariant): Future[BiosamplePrivateVariant] = {
    val action = (biosamplePrivateVariants returning biosamplePrivateVariants.map(_.id)
      into ((row, id) => row.copy(id = Some(id)))) += pv
    runQuery(action)
  }

  override def createAll(pvs: Seq[BiosamplePrivateVariant]): Future[Seq[BiosamplePrivateVariant]] = {
    val action = (biosamplePrivateVariants returning biosamplePrivateVariants.map(_.id)
      into ((row, id) => row.copy(id = Some(id)))) ++= pvs
    runQuery(action)
  }

  override def findBySample(sampleType: BiosampleSourceType, sampleId: Int): Future[Seq[BiosamplePrivateVariant]] =
    runQuery(biosamplePrivateVariants.filter(pv => pv.sampleType === sampleType && pv.sampleId === sampleId).result)

  override def findBySampleGuid(sampleGuid: UUID): Future[Seq[BiosamplePrivateVariant]] =
    runQuery(biosamplePrivateVariants.filter(_.sampleGuid === sampleGuid).result)

  override def findByVariantId(variantId: Int): Future[Seq[BiosamplePrivateVariant]] =
    runQuery(biosamplePrivateVariants.filter(_.variantId === variantId).result)

  override def findByTerminalHaplogroup(haplogroupId: Int): Future[Seq[BiosamplePrivateVariant]] =
    runQuery(biosamplePrivateVariants.filter(_.terminalHaplogroupId === haplogroupId).result)

  override def findActiveByVariantIds(variantIds: Set[Int], haplogroupType: HaplogroupType): Future[Seq[BiosamplePrivateVariant]] =
    runQuery(biosamplePrivateVariants
      .filter(pv => pv.variantId.inSet(variantIds) && pv.haplogroupType === haplogroupType && pv.status === (PrivateVariantStatus.Active: PrivateVariantStatus))
      .result)

  override def updateStatus(id: Int, status: PrivateVariantStatus): Future[Boolean] =
    runQuery(biosamplePrivateVariants.filter(_.id === id).map(_.status).update(status).map(_ > 0))

  override def countByVariant(variantId: Int, haplogroupType: HaplogroupType): Future[Int] =
    runQuery(biosamplePrivateVariants
      .filter(pv => pv.variantId === variantId && pv.haplogroupType === haplogroupType && pv.status === (PrivateVariantStatus.Active: PrivateVariantStatus))
      .length.result)
}
