package repositories

import jakarta.inject.Inject
import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.{Cytoband, GenbankContig, GenomeRegion, GenomeRegionVersion, StrMarker}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository interface for genome region data.
 * Provides access to structural annotations, cytobands, and STR markers.
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
   * Get all structural regions for a specific contig.
   */
  def getRegionsForContig(contigId: Int): Future[Seq[GenomeRegion]]

  /**
   * Get all cytobands for a specific contig.
   */
  def getCytobandsForContig(contigId: Int): Future[Seq[Cytoband]]

  /**
   * Get all STR markers for a specific contig.
   */
  def getStrMarkersForContig(contigId: Int): Future[Seq[StrMarker]]

  /**
   * Get all data for a build in a single composed query.
   * Returns contigs with their associated regions, cytobands, and STR markers.
   */
  def getFullBuildData(referenceGenome: String): Future[FullBuildData]
}

/**
 * Aggregated build data from the repository.
 */
case class FullBuildData(
  version: Option[GenomeRegionVersion],
  contigs: Seq[GenbankContig],
  regions: Map[Int, Seq[GenomeRegion]], // contigId -> regions
  cytobands: Map[Int, Seq[Cytoband]],   // contigId -> cytobands
  strMarkers: Map[Int, Seq[StrMarker]]  // contigId -> markers
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

  override def getRegionsForContig(contigId: Int): Future[Seq[GenomeRegion]] = {
    val query = genomeRegions
      .filter(_.genbankContigId === contigId)
      .sortBy(_.startPos)
      .result
    db.run(query)
  }

  override def getCytobandsForContig(contigId: Int): Future[Seq[Cytoband]] = {
    val query = cytobands
      .filter(_.genbankContigId === contigId)
      .sortBy(_.startPos)
      .result
    db.run(query)
  }

  override def getStrMarkersForContig(contigId: Int): Future[Seq[StrMarker]] = {
    val query = strMarkers
      .filter(_.genbankContigId === contigId)
      .sortBy(_.startPos)
      .result
    db.run(query)
  }

  override def getFullBuildData(referenceGenome: String): Future[FullBuildData] = {
    for {
      version <- getVersion(referenceGenome)
      contigs <- getContigsForBuild(referenceGenome)
      contigIds = contigs.flatMap(_.id)

      // Fetch all regions for the build's contigs
      allRegions <- if (contigIds.nonEmpty) {
        val query = genomeRegions
          .filter(_.genbankContigId.inSet(contigIds))
          .sortBy(r => (r.genbankContigId, r.startPos))
          .result
        db.run(query)
      } else Future.successful(Seq.empty)

      // Fetch all cytobands for the build's contigs
      allCytobands <- if (contigIds.nonEmpty) {
        val query = cytobands
          .filter(_.genbankContigId.inSet(contigIds))
          .sortBy(c => (c.genbankContigId, c.startPos))
          .result
        db.run(query)
      } else Future.successful(Seq.empty)

      // Fetch all STR markers for the build's contigs
      allStrMarkers <- if (contigIds.nonEmpty) {
        val query = strMarkers
          .filter(_.genbankContigId.inSet(contigIds))
          .sortBy(s => (s.genbankContigId, s.startPos))
          .result
        db.run(query)
      } else Future.successful(Seq.empty)

    } yield FullBuildData(
      version = version,
      contigs = contigs,
      regions = allRegions.groupBy(_.genbankContigId),
      cytobands = allCytobands.groupBy(_.genbankContigId),
      strMarkers = allStrMarkers.groupBy(_.genbankContigId)
    )
  }
}
