package repositories

import jakarta.inject.Inject
import models.dal.domain.genomics.SequenceLibrariesTable
import models.domain.genomics.SequenceLibrary
import play.api.db.slick.DatabaseConfigProvider
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

trait SequenceLibraryRepository {
  def create(library: SequenceLibrary): Future[SequenceLibrary]
}

class SequenceLibraryRepositoryImpl @Inject()(
                                           protected val dbConfigProvider: DatabaseConfigProvider
                                         )(implicit ec: ExecutionContext) extends SequenceLibraryRepository {

  private val dbConfig = dbConfigProvider.get[JdbcProfile]
  import dbConfig.profile.api._

  private val sequenceLibraries = TableQuery[SequenceLibrariesTable]

  def create(library: SequenceLibrary): Future[SequenceLibrary] = {
    val query = (sequenceLibraries returning sequenceLibraries.map(_.id)
      into ((library, id) => library.copy(id = Some(id)))
      ) += library
    dbConfig.db.run(query)
  }
}
