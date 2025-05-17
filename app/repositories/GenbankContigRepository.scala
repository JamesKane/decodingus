package repositories

import jakarta.inject.Inject
import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api.*
import models.GenbankContig
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}

import scala.concurrent.{ExecutionContext, Future}

trait GenbankContigRepository {
  def findByAccession(accession: String): Future[Option[GenbankContig]]
  def findById(id: Int): Future[Option[GenbankContig]]
  def getByAccessions(accessions: Seq[String]): Future[Seq[GenbankContig]]
}

class GenbankContigRepositoryImpl @Inject()(
                                             protected val dbConfigProvider: DatabaseConfigProvider
                                           )(implicit ec: ExecutionContext)
  extends GenbankContigRepository
    with HasDatabaseConfigProvider[MyPostgresProfile] {

  import models.dal.DatabaseSchema.genbankContigs

  def findByAccession(accession: String): Future[Option[GenbankContig]] = {
    val query = genbankContigs.filter(_.accession === accession).result.headOption
    db.run(query)
  }

  def findById(id: Int): Future[Option[GenbankContig]] = {
    val query = genbankContigs.filter(_.genbankContigId === id).result.headOption
    db.run(query)
  }

  def getByAccessions(accessions: Seq[String]): Future[Seq[GenbankContig]] = {
    val query = genbankContigs.filter(_.accession.inSet(accessions)).result
    db.run(query)
  }
}