package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.domain.genomics.TestTypeTargetRegion
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

trait TestTypeTargetRegionRepository {
  def findByTestTypeId(testTypeId: Int): Future[Seq[TestTypeTargetRegion]]
  def findByTestTypeCode(code: String): Future[Seq[TestTypeTargetRegion]]
  def findByContigName(contigName: String): Future[Seq[TestTypeTargetRegion]]
  def create(region: TestTypeTargetRegion): Future[TestTypeTargetRegion]
  def delete(id: Int): Future[Boolean]
}

@Singleton
class TestTypeTargetRegionRepositoryImpl @Inject()(
                                                     override protected val dbConfigProvider: DatabaseConfigProvider
                                                   )(implicit override protected val ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with TestTypeTargetRegionRepository {

  import models.dal.MyPostgresProfile.api.*

  private val regions = DatabaseSchema.domain.genomics.testTypeTargetRegions
  private val testTypes = DatabaseSchema.domain.genomics.testTypeDefinition

  override def findByTestTypeId(testTypeId: Int): Future[Seq[TestTypeTargetRegion]] = {
    db.run(regions.filter(_.testTypeId === testTypeId).result)
  }

  override def findByTestTypeCode(code: String): Future[Seq[TestTypeTargetRegion]] = {
    val query = regions
      .join(testTypes).on(_.testTypeId === _.id)
      .filter(_._2.code === code)
      .map(_._1)
    db.run(query.result)
  }

  override def findByContigName(contigName: String): Future[Seq[TestTypeTargetRegion]] = {
    db.run(regions.filter(_.contigName === contigName).result)
  }

  override def create(region: TestTypeTargetRegion): Future[TestTypeTargetRegion] = {
    db.run(
      (regions returning regions.map(_.id)
        into ((r, id) => r.copy(id = Some(id)))) += region
    )
  }

  override def delete(id: Int): Future[Boolean] = {
    db.run(regions.filter(_.id === id).delete.map(_ > 0))
  }
}
