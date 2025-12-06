package repositories

import com.vividsolutions.jts.geom.Point
import com.vividsolutions.jts.io.WKBReader
import jakarta.inject.{Inject, Singleton}
import models.api.{BiosampleWithOrigin, GeoCoord, PopulationInfo, SampleWithStudies, StudyWithHaplogroups}
import models.dal.domain.genomics.BiosamplesTable
import models.dal.{DatabaseSchema, MyPostgresProfile}
import models.domain.genomics.{Biosample, BiosampleType, SpecimenDonor}
import play.api.db.slick.DatabaseConfigProvider
import play.api.libs.json.Json
import slick.jdbc.{GetResult, SQLActionBuilder, SetParameter}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/**
 * Represents a repository interface for managing biosample data. This trait provides methods to interact
 * with biosamples and their related data, including fetching, pagination, and counting operations for publications.
 */
trait BiosampleRepository {
  /**
   * Retrieves a biosample and its associated specimen donor information by the given identifier.
   *
   * @param id the unique identifier of the biosample to retrieve
   * @return a future containing an optional tuple, where the first element is the biosample and the second element
   *         is an optional specimen donor associated with the biosample
   */
  def findById(id: Int): Future[Option[(Biosample, Option[SpecimenDonor])]]

  /**
   * Creates a new biosample record.
   *
   * @param biosample the biosample to create
   * @return a future containing the created biosample with its assigned ID
   */
  def create(biosample: Biosample): Future[Biosample]

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
  def findByAccession(accession: String): Future[Option[(Biosample, Option[SpecimenDonor])]]


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

  /**
   * Retrieves all samples with their associated studies and original haplogroup assignments
   *
   * @return a future containing a sequence of samples with their studies and assignments
   */
  def findAllWithStudies(): Future[Seq[SampleWithStudies]]

  def findByAliasOrAccession(query: String): Future[Option[(Biosample, Option[SpecimenDonor])]]

  /**
   * Retrieves a biosample by its GUID (Globally Unique Identifier).
   *
   * @param guid the UUID of the biosample to be retrieved
   * @return a future containing an optional biosample if found
   */
  def findByGuid(guid: UUID): Future[Option[(Biosample, Option[SpecimenDonor])]]

  def getAllGeoLocations: Future[Seq[(Point, Int)]]

  def delete(id: Int): Future[Boolean]
}

@Singleton
class BiosampleRepositoryImpl @Inject()(
                                         override protected val dbConfigProvider: DatabaseConfigProvider
                                       )(implicit override protected  val ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with BiosampleRepository {

  import models.dal.MyPostgresProfile.api.*

  private val biosamplesTable = DatabaseSchema.domain.genomics.biosamples
  private val specimenDonorsTable = DatabaseSchema.domain.genomics.specimenDonors

  private def readPoint(pgObj: AnyRef): Option[GeoCoord] = pgObj match {
    case null => None
    case _ =>
      try {
        val wkbReader = new WKBReader()
        val point = wkbReader.read(WKBReader.hexToBytes(pgObj.toString)).asInstanceOf[Point]
        Some(GeoCoord(point.getY, point.getX))
      } catch {
        case e: Exception =>
          println(s"Error reading WKB: ${pgObj.toString} - ${e.getMessage}")
          None
      }
  }

  // Helper method to get biosample with its donor
  private def getBiosampleWithDonor(query: Query[BiosamplesTable, Biosample, Seq]): Future[Option[(Biosample, Option[SpecimenDonor])]] = {
    db.run(
      query
        .joinLeft(specimenDonorsTable)
        .on(_.specimenDonorId === _.id)
        .result
        .headOption
        .map(_.map { case (biosample, donor) => (biosample, donor) })
    )
  }

  protected implicit val getBiosampleWithOriginResult: GetResult[BiosampleWithOrigin] = GetResult(r =>
    BiosampleWithOrigin(
      sampleName = r.nextStringOption(),
      accession = r.nextString(),
      sampleType = BiosampleType.valueOf(r.nextString()),
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
      },
      dateRangeStart = r.nextIntOption(),
      dateRangeEnd = r.nextIntOption()
    )
  )

  private def makeBaseQuery(publicationId: Int) =
    s"""
    WITH best_population AS (
      SELECT aa.sample_guid,
             p.population_name,
             aa.probability,
             am.method_name,
             ROW_NUMBER() OVER (PARTITION BY aa.sample_guid ORDER BY aa.probability DESC) as rn
      FROM ancestry_analysis aa
      JOIN population p ON p.population_id = aa.population_id
      JOIN analysis_method am ON am.analysis_method_id = aa.analysis_method_id
    )
    SELECT b.alias,
           b.sample_accession,
           sd.donor_type::text,
           sd.sex::text,
           sd.geocoord,
           boh.original_y_haplogroup AS y_haplogroup_name,
           boh.original_mt_haplogroup AS mt_haplogroup_name,
           sl.reads,
           sl.read_length,
           bp.population_name,
           bp.probability,
           bp.method_name,
           sd.date_range_start,
           sd.date_range_end
    FROM publication_biosample pb
    INNER JOIN public.biosample b ON b.id = pb.biosample_id
    LEFT JOIN specimen_donor sd ON sd.id = b.specimen_donor_id
    LEFT JOIN biosample_original_haplogroup boh ON boh.biosample_id = b.id
      AND boh.publication_id = $publicationId
    LEFT JOIN sequence_library sl on sl.sample_guid = b.sample_guid
    LEFT JOIN best_population bp ON bp.sample_guid = b.sample_guid AND bp.rn = 1
    WHERE pb.publication_id = $publicationId
    ORDER BY b.alias
    """


  override def findById(id: Int): Future[Option[(Biosample, Option[SpecimenDonor])]] = {
    getBiosampleWithDonor(biosamplesTable.filter(_.id === id))
  }

  def create(biosample: Biosample): Future[Biosample] = {
    val insertQuery = (biosamplesTable returning biosamplesTable.map(_.id)
      into ((bs, id) => bs.copy(id = Some(id))))
      .+=(biosample)

    db.run(insertQuery.transactionally)
  }

  override def update(biosample: Biosample): Future[Boolean] = {
    biosample.id match {
      case None => Future.successful(false)
      case Some(id) =>
        db.run(
          biosamplesTable
            .filter(_.id === id)
            .map(b => (
              b.alias,
              b.description,
              b.centerName,
              b.specimenDonorId,
              b.sourcePlatform,
              b.locked
            ))
            .update((
              biosample.alias,
              biosample.description,
              biosample.centerName,
              biosample.specimenDonorId,
              biosample.sourcePlatform,
              biosample.locked
            ))
            .map(_ > 0)
        )
    }
  }


  override def findByAccession(accession: String): Future[Option[(Biosample, Option[SpecimenDonor])]] = {
    getBiosampleWithDonor(biosamplesTable.filter(_.sampleAccession === accession))
  }

  override def findBiosamplesWithOriginForPublication(publicationId: Int): Future[Seq[BiosampleWithOrigin]] = {
    db.run(
      sql"""#${makeBaseQuery(publicationId)}"""
        .as[BiosampleWithOrigin]
    )
  }

  override def findPaginatedBiosamplesWithOriginForPublication(publicationId: Int, page: Int, pageSize: Int): Future[Seq[BiosampleWithOrigin]] = {
    val offset = (page - 1) * pageSize
    val paginatedQuery = s"""${makeBaseQuery(publicationId)} LIMIT $pageSize OFFSET $offset"""
    db.run(
      SQLActionBuilder(paginatedQuery, SetParameter.SetUnit)
        .as[BiosampleWithOrigin]
    )
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

        (for {
          existingBiosampleOpt <- query.result.headOption

          result <- existingBiosampleOpt match {
            case Some(existing) if existing.locked =>
              // If locked, return existing record without updates
              DBIO.successful(existing)

            case Some(existing) =>
              // For existing unlocked record, we only update biosample fields
              query
                .map(b => (b.alias, b.locked))
                .update((biosample.alias, biosample.locked))
                .map(_ => biosample.copy(id = existing.id))

            case None =>
              // For new records, insert both biosample and donor if needed
              for {
                // First handle the specimen donor if it exists
                donorId <- biosample.specimenDonorId match {
                  case Some(id) => DBIO.successful(Some(id))
                  case None => DBIO.successful(None)
                }

                // Then insert the biosample with the donor reference
                biosampleWithId <- (biosamplesTable returning biosamplesTable.map(_.id)
                  into ((bs, id) => bs.copy(id = Some(id))))
                  .+=(biosample.copy(specimenDonorId = donorId))
              } yield biosampleWithId
          }
        } yield result).transactionally
      }

      db.run(DBIO.sequence(actions).transactionally)
    }
  }

  override def findAllWithStudies(): Future[Seq[SampleWithStudies]] = {
    val query =
      """
      WITH sample_studies AS (
          SELECT
              b.id as biosample_id,
              b.alias as sample_name,
              b.sample_accession,
              sd.sex::text as sex,
              sd.geocoord,
              COALESCE(
                  jsonb_agg(
                      DISTINCT jsonb_build_object(
                          'accession', gs.accession,
                          'title', gs.title,
                          'centerName', gs.center_name,
                          'source', gs.source,
                          'yHaplogroup', boh.original_y_haplogroup,
                          'mtHaplogroup', boh.original_mt_haplogroup,
                          'notes', boh.notes
                      )
                  ) FILTER (WHERE gs.accession IS NOT NULL),
                  '[]'::jsonb
              ) as studies
          FROM biosample b
          LEFT JOIN specimen_donor sd ON sd.id = b.specimen_donor_id
          JOIN publication_biosample pb ON pb.biosample_id = b.id
          JOIN publication p ON p.id = pb.publication_id
          JOIN publication_ena_study pgs ON pgs.publication_id = pb.publication_id
          JOIN genomic_studies gs ON gs.id = pgs.genomic_study_id
          LEFT JOIN biosample_original_haplogroup boh ON
              boh.biosample_id = b.id AND
              boh.publication_id = p.id
          GROUP BY b.id, b.alias, b.sample_accession, sd.sex, sd.geocoord
      )
      SELECT
          sample_name,
          sample_accession,
          sex,
          geocoord,
          studies::text
      FROM sample_studies
      ORDER BY sample_accession
      """

    db.run(sql"""#$query""".as[(
      Option[String], String, Option[String],
        Option[AnyRef],
        String
      )]).map { results =>
      results.map { case (
        sampleName, accession, sex,
        point,
        studiesJson
        ) =>
        val studies = Json.parse(studiesJson).as[List[StudyWithHaplogroups]]

        SampleWithStudies(
          sampleName = sampleName,
          accession = accession,
          sex = sex,
          geoCoord = readPoint(point),
          studies = studies
        )
      }
    }
  }

  override def findByAliasOrAccession(query: String): Future[Option[(Biosample, Option[SpecimenDonor])]] = {
    val byAlias = biosamplesTable.filter(_.alias === query)
    val byAccession = biosamplesTable.filter(_.sampleAccession === query)
    getBiosampleWithDonor(byAlias union byAccession)
  }

  override def findByGuid(guid: UUID): Future[Option[(Biosample, Option[SpecimenDonor])]] = {
    getBiosampleWithDonor(biosamplesTable.filter(_.sampleGuid === guid))
  }

    def getAllGeoLocations: Future[Seq[(Point, Int)]] = {

      val query = specimenDonorsTable

        .filter(_.geocoord.isDefined)

        .groupBy(_.geocoord)

        .map { case (point, group) =>

          (point.asColumnOf[Point], group.length)

        }

  

      db.run(query.result)

    }

  

    override def delete(id: Int): Future[Boolean] = {

      db.run(biosamplesTable.filter(_.id === id).delete.map(_ > 0))

    }

  }