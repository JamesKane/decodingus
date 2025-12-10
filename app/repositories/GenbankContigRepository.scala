package repositories

import jakarta.inject.Inject
import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.GenbankContig
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository interface for interacting with Genbank contigs.
 * Provides methods to fetch contigs using different querying criteria.
 */
trait GenbankContigRepository {
  /**
   * Finds a GenbankContig by its accession number.
   *
   * @param accession The accession number of the desired GenbankContig.
   * @return A Future containing an Option of GenbankContig. The Option will 
   *         contain the GenbankContig if found, or None if not found.
   */
  def findByAccession(accession: String): Future[Option[GenbankContig]]

  /**
   * Finds a GenbankContig by its unique identifier.
   *
   * @param id The unique identifier of the GenbankContig to retrieve.
   * @return A Future containing an Option of GenbankContig. The Option will 
   *         contain the GenbankContig if found, or None if not found.
   */
  def findById(id: Int): Future[Option[GenbankContig]]

  /**
   * Retrieves a sequence of GenbankContig objects corresponding to the provided accession numbers.
   *
   * @param accessions A sequence of accession numbers for which GenbankContigs need to be fetched.
   * @return A Future containing a sequence of GenbankContig objects corresponding to the provided accession numbers. 
   *         The sequence may be empty if no matching GenbankContigs are found.
   */
  def getByAccessions(accessions: Seq[String]): Future[Seq[GenbankContig]]

  /**
   * Retrieves a sequence of GenbankContig objects corresponding to the provided common names.
   *
   * @param commonNames A sequence of common names for which GenbankContigs need to be fetched.
   * @return A Future containing a sequence of GenbankContig objects.
   */
  def findByCommonNames(commonNames: Seq[String]): Future[Seq[GenbankContig]]

  /**
   * Retrieves all GenbankContig objects.
   *
   * @return A Future containing a sequence of all GenbankContig objects.
   */
  def getAll: Future[Seq[GenbankContig]]

  /**
   * Retrieves Y-DNA and mtDNA contigs (chrY and chrM).
   *
   * @return A Future containing a sequence of Y and MT GenbankContig objects.
   */
  def getYAndMtContigs: Future[Seq[GenbankContig]]
}

class GenbankContigRepositoryImpl @Inject()(
                                             protected val dbConfigProvider: DatabaseConfigProvider
                                           )(implicit ec: ExecutionContext)
  extends GenbankContigRepository
    with HasDatabaseConfigProvider[MyPostgresProfile] {

  import models.dal.DatabaseSchema.domain.genomics.genbankContigs

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

  def findByCommonNames(commonNames: Seq[String]): Future[Seq[GenbankContig]] = {
    val query = genbankContigs.filter(_.commonName.inSet(commonNames)).result
    db.run(query)
  }

  def getAll: Future[Seq[GenbankContig]] = {
    val query = genbankContigs.sortBy(c => (c.referenceGenome, c.commonName)).result
    db.run(query)
  }

  def getYAndMtContigs: Future[Seq[GenbankContig]] = {
    // Include both "chrY"/"chrM" (GRCh38) and "Y"/"M" (GRCh37) naming conventions
    val query = genbankContigs
      .filter(c =>
        c.commonName.like("chrY%") || c.commonName.like("chrM%") ||
        c.commonName === "Y" || c.commonName === "M"
      )
      .sortBy(c => (c.referenceGenome, c.commonName))
      .result
    db.run(query)
  }
}