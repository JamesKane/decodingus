package repositories

import jakarta.inject.Inject
import models.dal.domain.genomics.SequenceFileChecksumsTable
import models.domain.genomics.SequenceFileChecksum
import play.api.db.slick.DatabaseConfigProvider
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

trait SequenceFileChecksumRepository {
  def create(checksum: SequenceFileChecksum): Future[SequenceFileChecksum]
}

class SequenceFileChecksumRepositoryImpl @Inject()(
                                           protected val dbConfigProvider: DatabaseConfigProvider
                                         )(implicit ec: ExecutionContext) extends SequenceFileChecksumRepository {

  private val dbConfig = dbConfigProvider.get[JdbcProfile]
  import dbConfig.profile.api._

  private val sequenceFileChecksums = TableQuery[SequenceFileChecksumsTable]

  def create(checksum: SequenceFileChecksum): Future[SequenceFileChecksum] = {
    val query = (sequenceFileChecksums returning sequenceFileChecksums.map(_.id)
      into ((checksum, id) => checksum.copy(id = Some(id)))
      ) += checksum
    dbConfig.db.run(query)
  }
}
