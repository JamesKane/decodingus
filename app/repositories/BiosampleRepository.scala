package repositories

import com.vividsolutions.jts.geom.Point
import com.vividsolutions.jts.io.WKBReader
import jakarta.inject.{Inject, Singleton}
import models.api.{BiosampleWithOrigin, GeoCoord, PopulationInfo}
import models.dal.{DatabaseSchema, MyPostgresProfile}
import models.domain.genomics.Biosample
import play.api.db.slick.DatabaseConfigProvider
import slick.jdbc.GetResult

import scala.concurrent.{ExecutionContext, Future}

/**
 * Represents a repository interface for managing biosample data. This trait provides methods to interact
 * with biosamples and their related data, including fetching, pagination, and counting operations for publications.
 */
trait BiosampleRepository {
  /**
   * Retrieves a biosample by its unique identifier.
   *
   * @param id the unique identifier of the biosample to be retrieved
   * @return a future containing an optional biosample if found, or none if not found
   */
  def findById(id: Int): Future[Option[Biosample]]

  /**
   * Updates specific fields of a biosample
   *
   * @param biosample The biosample with updated fields
   * @return Future[Boolean] indicating success
   */
  def update(biosample: Biosample): Future[Boolean]

  /**
   * Sets the lock status for a biosample
   *
   * @param id     The ID of the biosample to lock/unlock
   * @param locked The desired lock status
   * @return Future[Boolean] indicating success
   */
  def setLocked(id: Int, locked: Boolean): Future[Boolean]

  /**
   * Retrieves a biosample by its accession number.
   *
   * @param accession the accession number of the biosample
   * @return a future containing an optional biosample if found
   */
  def findByAccession(accession: String): Future[Option[Biosample]]


  /**
   * Retrieves all biosamples associated with a specific publication, including their origin metadata.
   *
   * @param publicationId the unique identifier of the publication for which biosamples are being queried
   * @return a future containing a sequence of biosamples with their origin information
   */
  def findBiosamplesWithOriginForPublication(publicationId: Int): Future[Seq[BiosampleWithOrigin]]

  /**
   * Retrieves a paginated list of biosamples, including their origin metadata, associated with a specific publication.
   *
   * @param publicationId the unique identifier of the publication for which biosamples are being queried
   * @param page          the page number to retrieve, starting from 1
   * @param pageSize      the number of items to include on each page
   * @return a future containing a sequence of biosamples with their origin information for the specified page
   */
  def findPaginatedBiosamplesWithOriginForPublication(publicationId: Int, page: Int, pageSize: Int): Future[Seq[BiosampleWithOrigin]]

  /**
   * Counts the number of biosamples associated with a specific publication.
   *
   * @param publicationId the unique identifier of the publication for which biosamples are being counted
   * @return a future containing the count of biosamples linked to the specified publication
   */
  def countBiosamplesForPublication(publicationId: Int): Future[Long]

  /**
   * Upserts (updates or inserts) multiple biosamples in a single transaction.
   *
   * @param biosamples sequence of biosamples to upsert
   * @return future sequence of the upserted biosamples with their IDs
   */
  def upsertMany(biosamples: Seq[Biosample]): Future[Seq[Biosample]]
}

@Singleton
class BiosampleRepositoryImpl @Inject()(
                                         override protected val dbConfigProvider: DatabaseConfigProvider
                                       )(implicit override protected  val ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with BiosampleRepository {

  import models.dal.MyPostgresProfile.api.*

  private val biosamplesTable = DatabaseSchema.domain.genomics.biosamples

  private def readPoint(pgObj: AnyRef): Option[GeoCoord] = pgObj match {
    case null => None
    case _ =>
      try {
        val wkbReader = new WKBReader()
        val point = wkbReader.read(WKBReader.hexToBytes(pgObj.toString)).asInstanceOf[Point]
        Some(GeoCoord(point.getX, point.getY))
      } catch {
        case e: Exception =>
          println(s"Error reading WKB: ${pgObj.toString} - ${e.getMessage}")
          None
      }
  }

  protected implicit val getBiosampleWithOriginResult: GetResult[BiosampleWithOrigin] = GetResult(r =>
    BiosampleWithOrigin(
      sampleName = r.nextStringOption(),
      accession = r.nextString(),
      sex = r.nextStringOption(),
      geoCoord = r.nextObjectOption().flatMap(readPoint),
      yDnaHaplogroup = r.nextStringOption(),
      mtDnaHaplogroup = r.nextStringOption(),
      reads = r.nextIntOption(),
      readLen = r.nextIntOption(),
      bestFitPopulation = (r.nextStringOption(), r.nextBigDecimalOption(), r.nextStringOption()) match {
        case (Some(popName), Some(prob), Some(methodName)) =>
          Some(PopulationInfo(popName, prob, methodName))
        case _ => None
      }
    )
  )

  private val bestPopulationCTE =
    """
    best_population AS (
      SELECT aa.sample_guid,
             p.population_name,
             aa.probability,
             am.method_name,
             ROW_NUMBER() OVER (PARTITION BY aa.sample_guid ORDER BY aa.probability DESC) as rn
      FROM ancestry_analysis aa
      JOIN population p ON p.population_id = aa.population_id
      JOIN analysis_method am ON am.analysis_method_id = aa.analysis_method_id
    )
  """

  private def makeBaseQuery(publicationId: Int) =
    s"""
    SELECT b.alias,
           b.sample_accession,
           b.sex,
           b.geocoord,
           MAX(CASE WHEN h.haplogroup_type = 'Y' THEN h.name END) AS y_haplogroup_name,
           MAX(CASE WHEN h.haplogroup_type = 'MT' THEN h.name END) AS mt_haplogroup_name,
           sl.reads,
           sl.read_length,
           bp.population_name,
           bp.probability,
           bp.method_name
    FROM publication_biosample pb
    INNER JOIN public.biosample b ON b.id = pb.biosample_id
    LEFT JOIN biosample_haplogroup bh ON bh.sample_guid = b.sample_guid
    LEFT JOIN haplogroup h ON h.haplogroup_id = bh.y_haplogroup_id AND h.haplogroup_type IN ('Y', 'MT')
    LEFT JOIN sequence_library sl on sl.sample_guid = b.sample_guid
    LEFT JOIN best_population bp ON bp.sample_guid = b.sample_guid AND bp.rn = 1
    WHERE pb.publication_id = $publicationId
    GROUP BY b.alias, b.sample_accession, b.sex, b.geocoord, sl.reads, sl.read_length,
        bp.population_name, bp.probability, bp.method_name
    ORDER BY b.alias
  """

  override def findById(id: Int): Future[Option[Biosample]] = {
    db.run(biosamplesTable.filter(_.id === id).result.headOption)
  }

  override def update(biosample: Biosample): Future[Boolean] = {
    biosample.id match {
      case None => Future.successful(false)
      case Some(id) =>
        db.run(
          biosamplesTable
            .filter(_.id === id)
            .map(b => (b.sex, b.geocoord, b.alias, b.locked))
            .update((biosample.sex, biosample.geocoord, biosample.alias, biosample.locked))
            .map(_ > 0)
        )
    }
  }

  override def findByAccession(accession: String): Future[Option[Biosample]] = {
    db.run(biosamplesTable.filter(_.sampleAccession === accession).result.headOption)
  }

  override def findBiosamplesWithOriginForPublication(publicationId: Int): Future[Seq[BiosampleWithOrigin]] = {
    withCTE[BiosampleWithOrigin](bestPopulationCTE, makeBaseQuery(publicationId))
  }

  override def findPaginatedBiosamplesWithOriginForPublication(
                                                                publicationId: Int,
                                                                page: Int,
                                                                pageSize: Int
                                                              ): Future[Seq[BiosampleWithOrigin]] = {
    val offset = (page - 1) * pageSize
    val paginatedQuery =
      s"""
      ${makeBaseQuery(publicationId)}
      LIMIT $pageSize OFFSET $offset
    """
    withCTE[BiosampleWithOrigin](bestPopulationCTE, paginatedQuery)
  }

  override def countBiosamplesForPublication(publicationId: Int): Future[Long] = {
    val query =
      s"""
      SELECT COUNT(DISTINCT b.id)
      FROM publication_biosample pb
      INNER JOIN public.biosample b ON b.id = pb.biosample_id
      WHERE pb.publication_id = $publicationId
    """
    rawSQL[Long](query).map(_.head)
  }
  
  override def setLocked(id: Int, locked: Boolean): Future[Boolean] = {
    db.run(
      biosamplesTable
        .filter(_.id === id)
        .map(_.locked)
        .update(locked)
        .map(_ > 0)
    )
  }

  /**
   * Upserts multiple biosamples, respecting any existing locks
   * Locked samples will not have their metadata updated
   *
   * @param biosamples sequence of biosamples to upsert
   * @return future sequence of the upserted biosamples with their IDs
   */
  def upsertMany(biosamples: Seq[Biosample]): Future[Seq[Biosample]] = {
    if (biosamples.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      val actions = biosamples.map { biosample =>
        val query = biosamplesTable.filter(_.sampleAccession === biosample.sampleAccession)

        (query.result.headOption.flatMap {
          case Some(existing) if existing.locked =>
            // If locked, return existing record without updates
            DBIO.successful(existing)

          case Some(existing) =>
            // Update existing unlocked record
            query.update(biosample.copy(
              id = existing.id,
              // Preserve existing values for fields we want to protect
              sex = existing.sex.orElse(biosample.sex),
              geocoord = existing.geocoord.orElse(biosample.geocoord),
              locked = existing.locked
            )).map(_ => biosample.copy(id = existing.id))

          case None =>
            // Insert new record
            (biosamplesTable returning biosamplesTable.map(_.id)
              into ((bs, id) => bs.copy(id = Some(id))))
              .+=(biosample)
        }).transactionally
      }

      db.run(DBIO.sequence(actions).transactionally)
    }
  }
}