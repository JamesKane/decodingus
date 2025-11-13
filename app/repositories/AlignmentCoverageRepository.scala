package repositories

import jakarta.inject.Inject
import models.dal.domain.genomics.AlignmentCoverageTable
import models.domain.genomics.AlignmentCoverage
import play.api.db.slick.DatabaseConfigProvider
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

trait AlignmentCoverageRepository {
  def create(coverage: AlignmentCoverage): Future[AlignmentCoverage]
}

class AlignmentCoverageRepositoryImpl @Inject()(
                                           protected val dbConfigProvider: DatabaseConfigProvider
                                         )(implicit ec: ExecutionContext) extends AlignmentCoverageRepository {

  private val dbConfig = dbConfigProvider.get[JdbcProfile]
  import dbConfig.profile.api._

  private val alignmentCoverages = TableQuery[AlignmentCoverageTable]

  def create(coverage: AlignmentCoverage): Future[AlignmentCoverage] = {
    val query = (alignmentCoverages returning alignmentCoverages) += coverage
    dbConfig.db.run(query)
  }
}
