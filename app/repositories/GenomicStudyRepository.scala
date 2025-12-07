package repositories

import jakarta.inject.Inject
import models.dal.DatabaseSchema
import models.dal.MyPostgresProfile.api.*
import models.domain.publications.GenomicStudy
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.JdbcProfile

import scala.concurrent.{ExecutionContext, Future}

/**
 * A repository trait for genomic study-related data operations, providing a contract for implementing
 * persistence and retrieval logic specific to genomic studies.
 *
 * This trait defines methods for interacting with genomic study data, such as fetching studies
 * by their ENA accession number, retrieving all study accessions, saving a study, or finding
 * the database identifier (ID) from an accession number. The actual implementation of these
 * methods can interact with various data sources, including relational databases or APIs.
 *
 * Methods:
 *
 * - `findByAccession`: Retrieves a genomic study by its accession number.
 *
 * - `getAllAccessions`: Fetches all available accession numbers of genomic studies.
 *
 * - `saveStudy`: Persists a genomic study entity to the data source and returns the saved entity.
 *
 * - `findIdByAccession`: Retrieves the unique identifier of a study based on its accession number.
 */
trait GenomicStudyRepository {
  /**
   * Retrieves a genomic study matching the given accession number.
   *
   * @param accession The accession number of the genomic study to be retrieved.
   * @return A Future containing an Option of the genomic study. The Option will be:
   *         - Some(GenomicStudy) if a study matching the accession number is found.
   *         - None if no study is found with the given accession number.
   */
  def findByAccession(accession: String): Future[Option[GenomicStudy]]

  /**
   * Retrieves all available accession numbers of genomic studies.
   *
   * @return A Future containing a sequence of strings, where each string represents
   *         an accession number of a genomic study.
   */
  def getAllAccessions: Future[Seq[String]]

  /**
   * Persists the provided genomic study to the data source.
   *
   * The method saves the given `GenomicStudy` instance, including its metadata and details,
   * to the underlying data source (e.g., a database or API). If the operation is successful,
   * the saved study entity is returned as part of the `Future`.
   *
   * @param study The instance of `GenomicStudy` to be saved. It contains metadata such as
   *              accession, title, center name, study name, and other relevant information.
   * @return A `Future` containing the saved `GenomicStudy` instance. The returned instance may
   *         include additional information such as a generated unique identifier if applicable.
   */
  def saveStudy(study: GenomicStudy): Future[GenomicStudy]

  /**
   * Retrieves the unique identifier associated with the given genomic study accession number.
   *
   * @param accession The accession number of the genomic study whose identifier is to be retrieved.
   * @return A Future containing an Option of the unique identifier (Int).
   *         The Option will be:
   *         - Some(Int) if an identifier matching the given accession number is found.
   *         - None if no identifier is found for the given accession number.
   */
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