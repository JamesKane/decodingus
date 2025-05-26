package repositories

import jakarta.inject.Inject
import models.dal.MyPostgresProfile.api.*
import models.dal.domain.publications.EnaStudiesTable
import models.domain.publications.EnaStudy
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

trait EnaStudyRepository {
  def findByAccession(accession: String): Future[Option[EnaStudy]]
  def getAllAccessions: Future[Seq[String]]
  def saveStudy(study: EnaStudy): Future[EnaStudy]
}

class EnaStudyRepositoryImpl @Inject()(
                                        protected val dbConfigProvider: DatabaseConfigProvider
                                      )(implicit ec: ExecutionContext)
  extends EnaStudyRepository
    with HasDatabaseConfigProvider[JdbcProfile] {

  private val enaStudies = TableQuery[EnaStudiesTable]

  override def findByAccession(accession: String): Future[Option[EnaStudy]] = {
    db.run(enaStudies.filter(_.accession === accession).result.headOption)
  }

  override def getAllAccessions: Future[Seq[String]] = {
    db.run(enaStudies.map(_.accession).result)
  }

  override def saveStudy(study: EnaStudy): Future[EnaStudy] = {
    val query = enaStudies.filter(_.accession === study.accession)

    db.run(query.result.headOption).flatMap {
      case Some(existingStudy) =>
        val studyToUpdate = study.copy(id = existingStudy.id)
        db.run(enaStudies.filter(_.id === existingStudy.id).update(studyToUpdate))
          .map(_ => studyToUpdate)
      case None =>
        db.run((enaStudies returning enaStudies.map(_.id)
          into ((study, id) => study.copy(id = Some(id)))) += study)
    }
  }
}