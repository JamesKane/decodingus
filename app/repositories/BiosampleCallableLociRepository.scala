package repositories

import jakarta.inject.Inject
import models.domain.genomics.BiosampleCallableLoci
import play.api.Logging
import play.api.db.slick.DatabaseConfigProvider

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

trait BiosampleCallableLociRepository {
  def findBySample(sampleType: String, sampleId: Int, chromosome: String): Future[Option[BiosampleCallableLoci]]
  def findBySampleGuid(sampleGuid: UUID, chromosome: String): Future[Option[BiosampleCallableLoci]]
  def upsert(loci: BiosampleCallableLoci): Future[Int]
  def findAllForSample(sampleType: String, sampleId: Int): Future[Seq[BiosampleCallableLoci]]
}

class BiosampleCallableLociRepositoryImpl @Inject()(
  dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with BiosampleCallableLociRepository
    with Logging {

  import models.dal.DatabaseSchema.domain.genomics.biosampleCallableLoci
  import models.dal.MyPostgresProfile.api.*

  override def findBySample(sampleType: String, sampleId: Int, chromosome: String): Future[Option[BiosampleCallableLoci]] =
    runQuery(biosampleCallableLoci
      .filter(r => r.sampleType === sampleType && r.sampleId === sampleId && r.chromosome === chromosome)
      .result.headOption)

  override def findBySampleGuid(sampleGuid: UUID, chromosome: String): Future[Option[BiosampleCallableLoci]] =
    runQuery(biosampleCallableLoci
      .filter(r => r.sampleGuid === sampleGuid && r.chromosome === chromosome)
      .result.headOption)

  override def upsert(loci: BiosampleCallableLoci): Future[Int] =
    runQuery(biosampleCallableLoci.insertOrUpdate(loci))

  override def findAllForSample(sampleType: String, sampleId: Int): Future[Seq[BiosampleCallableLoci]] =
    runQuery(biosampleCallableLoci
      .filter(r => r.sampleType === sampleType && r.sampleId === sampleId)
      .result)
}
