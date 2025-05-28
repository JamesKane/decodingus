package repositories

import jakarta.inject.Inject
import models.dal.DatabaseSchema
import models.dal.MyPostgresProfile.api.*
import models.domain.publications.GenomicStudy
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

trait GenomicStudyRepository {
  def findByAccession(accession: String): Future[Option[GenomicStudy]]
  def getAllAccessions: Future[Seq[String]]
  def saveStudy(study: GenomicStudy): Future[GenomicStudy]
  def findIdByAccession(accession: String): Future[Option[Int]]
}

class GenomicStudyRepositoryImpl @Inject()(
                                        protected val dbConfigProvider: DatabaseConfigProvider
                                      )(implicit ec: ExecutionContext)
  extends GenomicStudyRepository
    with HasDatabaseConfigProvider[JdbcProfile] {

  private val genomicStudies = DatabaseSchema.domain.publications.genomicStudies

  override def findByAccession(accession: String): Future[Option[GenomicStudy]] = {
    db.run(genomicStudies.filter(_.accession === accession).result.headOption)
  }

  override def findIdByAccession(accession: String): Future[Option[Int]] = {
    db.run(genomicStudies.filter(_.accession === accession).map(_.id).result.headOption)
  }

  override def getAllAccessions: Future[Seq[String]] = {
    db.run(genomicStudies.map(_.accession).result)
  }

  override def saveStudy(study: GenomicStudy): Future[GenomicStudy] = {
    val query = genomicStudies.filter(_.accession === study.accession)

    db.run(query.result.headOption).flatMap {
      case Some(existingStudy) =>
        val studyToUpdate = study.copy(id = existingStudy.id)
        db.run(genomicStudies.filter(_.id === existingStudy.id).update(studyToUpdate))
          .map(_ => studyToUpdate)
      case None =>
        db.run((genomicStudies returning genomicStudies.map(_.id)
          into ((study, id) => study.copy(id = Some(id)))) += study)
    }
  }
}