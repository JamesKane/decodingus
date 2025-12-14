package repositories

import jakarta.inject.Inject
import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.{GenbankContig, GenomeRegion, GenomeRegionVersion}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.api.libs.json.Json

import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository interface for genome region data.
 * Provides access to structural annotations and cytobands (now unified).
 */
trait GenomeRegionsRepository {

  /**
   * Get the data version for a reference genome build.
   */
  def getVersion(referenceGenome: String): Future[Option[GenomeRegionVersion]]

  /**
   * Get all contigs (chromosomes) for a reference genome build.
   */
  def getContigsForBuild(referenceGenome: String): Future[Seq[GenbankContig]]

  /**
   * Get all regions (including cytobands) that have coordinates for the specified build.
   */
  def getRegionsForBuild(referenceGenome: String): Future[Seq[GenomeRegion]]

  /**
   * Get all data for a build in a single composed query.
   */
  def getFullBuildData(referenceGenome: String): Future[FullBuildData]

  // ============================================================================
  // GenomeRegion CRUD operations
  // ============================================================================

  def findRegionById(id: Int): Future[Option[GenomeRegion]]
  
  // Note: Pagination with JSONB filtering can be slow without specific indices.
  // For the management API, we might iterate all or allow filtering by type.
  def findRegions(regionType: Option[String], build: Option[String], offset: Int, limit: Int): Future[Seq[GenomeRegion]]
  
  def countRegions(regionType: Option[String], build: Option[String]): Future[Int]
  
  def createRegion(region: GenomeRegion): Future[Int]
  def updateRegion(id: Int, region: GenomeRegion): Future[Boolean]
  def deleteRegion(id: Int): Future[Boolean]
  def bulkCreateRegions(regions: Seq[GenomeRegion]): Future[Seq[Int]]
}

/**
 * Aggregated build data from the repository.
 */
case class FullBuildData(
  version: Option[GenomeRegionVersion],
  contigs: Seq[GenbankContig],
  regions: Seq[GenomeRegion]
)

class GenomeRegionsRepositoryImpl @Inject()(
  protected val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
  extends GenomeRegionsRepository
    with HasDatabaseConfigProvider[MyPostgresProfile] {

  import models.dal.DatabaseSchema.domain.genomics.*

  override def getVersion(referenceGenome: String): Future[Option[GenomeRegionVersion]] = {
    val query = genomeRegionVersions
      .filter(_.referenceGenome === referenceGenome)
      .result
      .headOption
    db.run(query)
  }

  override def getContigsForBuild(referenceGenome: String): Future[Seq[GenbankContig]] = {
    val query = genbankContigs
      .filter(_.referenceGenome === referenceGenome)
      .sortBy(_.commonName)
      .result
    db.run(query)
  }

  override def getRegionsForBuild(referenceGenome: String): Future[Seq[GenomeRegion]] = {
    // Select regions where coordinates -> buildName exists
    val query = genomeRegions
      .filter(r => r.coordinates ?? referenceGenome)
      .result
    db.run(query)
  }

  override def getFullBuildData(referenceGenome: String): Future[FullBuildData] = {
    for {
      version <- getVersion(referenceGenome)
      contigs <- getContigsForBuild(referenceGenome)
      regions <- getRegionsForBuild(referenceGenome)
    } yield FullBuildData(
      version = version,
      contigs = contigs,
      regions = regions
    )
  }

  // ============================================================================
  // GenomeRegion CRUD implementations
  // ============================================================================

  override def findRegionById(id: Int): Future[Option[GenomeRegion]] = {
    db.run(genomeRegions.filter(_.id === id).result.headOption)
  }

  override def findRegions(regionType: Option[String], build: Option[String], offset: Int, limit: Int): Future[Seq[GenomeRegion]] = {
    var query = genomeRegions.sortBy(_.id)

    if (regionType.isDefined) {
      query = query.filter(_.regionType === regionType.get)
    }

    if (build.isDefined) {
       query = query.filter(r => r.coordinates ?? build.get)
    }

    db.run(query.drop(offset).take(limit).result)
  }

  override def countRegions(regionType: Option[String], build: Option[String]): Future[Int] = {
    var query = genomeRegions.sortBy(_.id) // Sort irrelevant for count but type checks

    if (regionType.isDefined) {
      query = query.filter(_.regionType === regionType.get)
    }

    if (build.isDefined) {
      query = query.filter(r => r.coordinates ?? build.get)
    }

    db.run(query.length.result)
  }

  override def createRegion(region: GenomeRegion): Future[Int] = {
    db.run((genomeRegions returning genomeRegions.map(_.id)) += region)
  }

  override def updateRegion(id: Int, region: GenomeRegion): Future[Boolean] = {
    val query = genomeRegions.filter(_.id === id).map(r =>
      (r.regionType, r.name, r.coordinates, r.properties)
    ).update((region.regionType, region.name, Json.toJson(region.coordinates), region.properties))
    db.run(query).map(_ > 0)
  }

  override def deleteRegion(id: Int): Future[Boolean] = {
    db.run(genomeRegions.filter(_.id === id).delete).map(_ > 0)
  }

  override def bulkCreateRegions(regions: Seq[GenomeRegion]): Future[Seq[Int]] = {
    db.run((genomeRegions returning genomeRegions.map(_.id)) ++= regions)
  }
}