package repositories

import jakarta.inject.Inject
import models.dal.domain.genomics.StrMutationRate
import play.api.Logging
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

trait StrMutationRateRepository {
  def findByMarker(markerName: String): Future[Option[StrMutationRate]]
  def findByMarkers(markerNames: Seq[String]): Future[Seq[StrMutationRate]]
  def findAll(): Future[Seq[StrMutationRate]]
  def upsert(rate: StrMutationRate): Future[Int]
}

class StrMutationRateRepositoryImpl @Inject()(
  dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with StrMutationRateRepository
    with Logging {

  import models.dal.DatabaseSchema.domain.genomics.strMutationRates
  import models.dal.MyPostgresProfile.api.*

  override def findByMarker(markerName: String): Future[Option[StrMutationRate]] =
    runQuery(strMutationRates.filter(_.markerName === markerName).result.headOption)

  override def findByMarkers(markerNames: Seq[String]): Future[Seq[StrMutationRate]] =
    runQuery(strMutationRates.filter(_.markerName.inSet(markerNames)).result)

  override def findAll(): Future[Seq[StrMutationRate]] =
    runQuery(strMutationRates.result)

  override def upsert(rate: StrMutationRate): Future[Int] =
    runQuery(strMutationRates.insertOrUpdate(rate))
}
