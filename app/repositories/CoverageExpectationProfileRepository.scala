package repositories

import jakarta.inject.Inject
import models.dal.DatabaseSchema
import models.domain.genomics.CoverageExpectationProfile
import play.api.Logging
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

trait CoverageExpectationProfileRepository {
  def findByTestTypeId(testTypeId: Int): Future[Seq[CoverageExpectationProfile]]
  def findByTestTypeAndContig(testTypeId: Int, contigName: String): Future[Seq[CoverageExpectationProfile]]
  def findByTestTypeContigAndClass(testTypeId: Int, contigName: String, variantClass: String): Future[Option[CoverageExpectationProfile]]
  def create(profile: CoverageExpectationProfile): Future[CoverageExpectationProfile]
  def update(profile: CoverageExpectationProfile): Future[Boolean]
  def delete(id: Int): Future[Boolean]
}

class CoverageExpectationProfileRepositoryImpl @Inject()(
  dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with CoverageExpectationProfileRepository
    with Logging {

  import models.dal.MyPostgresProfile.api.*

  private val profiles = DatabaseSchema.domain.genomics.coverageExpectationProfiles

  override def findByTestTypeId(testTypeId: Int): Future[Seq[CoverageExpectationProfile]] =
    runQuery(profiles.filter(_.testTypeId === testTypeId).result)

  override def findByTestTypeAndContig(testTypeId: Int, contigName: String): Future[Seq[CoverageExpectationProfile]] =
    runQuery(profiles.filter(p => p.testTypeId === testTypeId && p.contigName === contigName).result)

  override def findByTestTypeContigAndClass(testTypeId: Int, contigName: String, variantClass: String): Future[Option[CoverageExpectationProfile]] =
    runQuery(profiles.filter(p =>
      p.testTypeId === testTypeId && p.contigName === contigName && p.variantClass === variantClass
    ).result.headOption)

  override def create(profile: CoverageExpectationProfile): Future[CoverageExpectationProfile] =
    runQuery(
      (profiles returning profiles.map(_.id) into ((p, id) => p.copy(id = Some(id)))) += profile
    )

  override def update(profile: CoverageExpectationProfile): Future[Boolean] = profile.id match {
    case None => Future.successful(false)
    case Some(id) =>
      runQuery(
        profiles.filter(_.id === id)
          .map(p => (p.minDepthHigh, p.minDepthMedium, p.minDepthLow, p.minCoveragePct, p.minMappingQuality, p.minCallablePct, p.notes))
          .update((profile.minDepthHigh, profile.minDepthMedium, profile.minDepthLow, profile.minCoveragePct, profile.minMappingQuality, profile.minCallablePct, profile.notes))
      ).map(_ > 0)
  }

  override def delete(id: Int): Future[Boolean] =
    runQuery(profiles.filter(_.id === id).delete).map(_ > 0)
}
