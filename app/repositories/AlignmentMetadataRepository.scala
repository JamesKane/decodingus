package repositories

import jakarta.inject.Inject
import models.dal.domain.genomics.AlignmentMetadataTable
import models.domain.genomics.AlignmentMetadata
import play.api.db.slick.DatabaseConfigProvider
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

trait AlignmentMetadataRepository {
  def create(metadata: AlignmentMetadata): Future[AlignmentMetadata]
}

class AlignmentMetadataRepositoryImpl @Inject()(
                                             protected val dbConfigProvider: DatabaseConfigProvider
                                           )(implicit ec: ExecutionContext) extends AlignmentMetadataRepository {

  private val dbConfig = dbConfigProvider.get[JdbcProfile]
  import dbConfig.profile.api._

  private val alignmentMetadata = TableQuery[AlignmentMetadataTable]

  def create(metadata: AlignmentMetadata): Future[AlignmentMetadata] = {
    val query = (alignmentMetadata returning alignmentMetadata.map(_.id)
      into ((metadata, id) => metadata.copy(id = Some(id)))
      ) += metadata
    dbConfig.db.run(query)
  }
}
