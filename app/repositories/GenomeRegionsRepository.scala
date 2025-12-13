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

  // ============================================================================
  // GenomeRegion CRUD operations
  // ============================================================================

  def findRegionById(id: Int): Future[Option[GenomeRegion]]
  def findRegionByIdWithContig(id: Int): Future[Option[(GenomeRegion, GenbankContig)]]
  def findRegionsByBuild(referenceGenome: String, offset: Int, limit: Int): Future[Seq[(GenomeRegion, GenbankContig)]]
  def countRegionsByBuild(referenceGenome: Option[String]): Future[Int]
  def createRegion(region: GenomeRegion): Future[Int]
  def updateRegion(id: Int, region: GenomeRegion): Future[Boolean]
  def deleteRegion(id: Int): Future[Boolean]
  def bulkCreateRegions(regions: Seq[GenomeRegion]): Future[Seq[Int]]

  // ============================================================================
  // Cytoband CRUD operations
  // ============================================================================

  def findCytobandById(id: Int): Future[Option[Cytoband]]
  def findCytobandByIdWithContig(id: Int): Future[Option[(Cytoband, GenbankContig)]]
  def findCytobandsByBuild(referenceGenome: String, offset: Int, limit: Int): Future[Seq[(Cytoband, GenbankContig)]]
  def countCytobandsByBuild(referenceGenome: Option[String]): Future[Int]
  def createCytoband(cytoband: Cytoband): Future[Int]
  def updateCytoband(id: Int, cytoband: Cytoband): Future[Boolean]
  def deleteCytoband(id: Int): Future[Boolean]
  def bulkCreateCytobands(cytobands: Seq[Cytoband]): Future[Seq[Int]]

  // ============================================================================
  // StrMarker CRUD operations
  // ============================================================================

  def findStrMarkerById(id: Int): Future[Option[StrMarker]]
  def findStrMarkerByIdWithContig(id: Int): Future[Option[(StrMarker, GenbankContig)]]
  def findStrMarkersByBuild(referenceGenome: String, offset: Int, limit: Int): Future[Seq[(StrMarker, GenbankContig)]]
  def countStrMarkersByBuild(referenceGenome: Option[String]): Future[Int]
  def createStrMarker(marker: StrMarker): Future[Int]
  def updateStrMarker(id: Int, marker: StrMarker): Future[Boolean]
  def deleteStrMarker(id: Int): Future[Boolean]
  def bulkCreateStrMarkers(markers: Seq[StrMarker]): Future[Seq[Int]]
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

  // STR marker table was replaced in schema migration - stub for now
  override def getStrMarkersForContig(contigId: Int): Future[Seq[StrMarker]] = {
    Future.successful(Seq.empty)
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

    } yield FullBuildData(
      version = version,
      contigs = contigs,
      regions = allRegions.groupBy(_.genbankContigId),
      cytobands = allCytobands.groupBy(_.genbankContigId),
      strMarkers = Map.empty  // STR marker table was replaced in schema migration
    )
  }

  // ============================================================================
  // GenomeRegion CRUD implementations
  // ============================================================================

  override def findRegionById(id: Int): Future[Option[GenomeRegion]] = {
    db.run(genomeRegions.filter(_.id === id).result.headOption)
  }

  override def findRegionByIdWithContig(id: Int): Future[Option[(GenomeRegion, GenbankContig)]] = {
    val query = for {
      region <- genomeRegions if region.id === id
      contig <- genbankContigs if contig.genbankContigId === region.genbankContigId
    } yield (region, contig)
    db.run(query.result.headOption)
  }

  override def findRegionsByBuild(referenceGenome: String, offset: Int, limit: Int): Future[Seq[(GenomeRegion, GenbankContig)]] = {
    val query = for {
      region <- genomeRegions
      contig <- genbankContigs if contig.genbankContigId === region.genbankContigId && contig.referenceGenome === referenceGenome
    } yield (region, contig)
    db.run(query.sortBy(_._1.startPos).drop(offset).take(limit).result)
  }

  override def countRegionsByBuild(referenceGenome: Option[String]): Future[Int] = {
    val query = referenceGenome match {
      case Some(ref) =>
        for {
          region <- genomeRegions
          contig <- genbankContigs if contig.genbankContigId === region.genbankContigId && contig.referenceGenome === ref
        } yield region
      case None => genomeRegions
    }
    db.run(query.length.result)
  }

  override def createRegion(region: GenomeRegion): Future[Int] = {
    db.run((genomeRegions returning genomeRegions.map(_.id)) += region)
  }

  override def updateRegion(id: Int, region: GenomeRegion): Future[Boolean] = {
    val query = genomeRegions.filter(_.id === id).map(r =>
      (r.genbankContigId, r.regionType, r.name, r.startPos, r.endPos, r.modifier)
    ).update((region.genbankContigId, region.regionType, region.name, region.startPos, region.endPos, region.modifier))
    db.run(query).map(_ > 0)
  }

  override def deleteRegion(id: Int): Future[Boolean] = {
    db.run(genomeRegions.filter(_.id === id).delete).map(_ > 0)
  }

  override def bulkCreateRegions(regions: Seq[GenomeRegion]): Future[Seq[Int]] = {
    db.run((genomeRegions returning genomeRegions.map(_.id)) ++= regions)
  }

  // ============================================================================
  // Cytoband CRUD implementations
  // ============================================================================

  override def findCytobandById(id: Int): Future[Option[Cytoband]] = {
    db.run(cytobands.filter(_.id === id).result.headOption)
  }

  override def findCytobandByIdWithContig(id: Int): Future[Option[(Cytoband, GenbankContig)]] = {
    val query = for {
      cytoband <- cytobands if cytoband.id === id
      contig <- genbankContigs if contig.genbankContigId === cytoband.genbankContigId
    } yield (cytoband, contig)
    db.run(query.result.headOption)
  }

  override def findCytobandsByBuild(referenceGenome: String, offset: Int, limit: Int): Future[Seq[(Cytoband, GenbankContig)]] = {
    val query = for {
      cytoband <- cytobands
      contig <- genbankContigs if contig.genbankContigId === cytoband.genbankContigId && contig.referenceGenome === referenceGenome
    } yield (cytoband, contig)
    db.run(query.sortBy(_._1.startPos).drop(offset).take(limit).result)
  }

  override def countCytobandsByBuild(referenceGenome: Option[String]): Future[Int] = {
    val query = referenceGenome match {
      case Some(ref) =>
        for {
          cytoband <- cytobands
          contig <- genbankContigs if contig.genbankContigId === cytoband.genbankContigId && contig.referenceGenome === ref
        } yield cytoband
      case None => cytobands
    }
    db.run(query.length.result)
  }

  override def createCytoband(cytoband: Cytoband): Future[Int] = {
    db.run((cytobands returning cytobands.map(_.id)) += cytoband)
  }

  override def updateCytoband(id: Int, cytoband: Cytoband): Future[Boolean] = {
    val query = cytobands.filter(_.id === id).map(c =>
      (c.genbankContigId, c.name, c.startPos, c.endPos, c.stain)
    ).update((cytoband.genbankContigId, cytoband.name, cytoband.startPos, cytoband.endPos, cytoband.stain))
    db.run(query).map(_ > 0)
  }

  override def deleteCytoband(id: Int): Future[Boolean] = {
    db.run(cytobands.filter(_.id === id).delete).map(_ > 0)
  }

  override def bulkCreateCytobands(cytobandList: Seq[Cytoband]): Future[Seq[Int]] = {
    db.run((cytobands returning cytobands.map(_.id)) ++= cytobandList)
  }

  // ============================================================================
  // StrMarker CRUD implementations
  // NOTE: STR marker table was replaced with str_mutation_rate in schema migration.
  // These methods are stubbed to maintain API compatibility.
  // ============================================================================

  override def findStrMarkerById(id: Int): Future[Option[StrMarker]] = {
    Future.successful(None)
  }

  override def findStrMarkerByIdWithContig(id: Int): Future[Option[(StrMarker, GenbankContig)]] = {
    Future.successful(None)
  }

  override def findStrMarkersByBuild(referenceGenome: String, offset: Int, limit: Int): Future[Seq[(StrMarker, GenbankContig)]] = {
    Future.successful(Seq.empty)
  }

  override def countStrMarkersByBuild(referenceGenome: Option[String]): Future[Int] = {
    Future.successful(0)
  }

  override def createStrMarker(marker: StrMarker): Future[Int] = {
    Future.failed(new UnsupportedOperationException("STR marker table has been replaced with str_mutation_rate"))
  }

  override def updateStrMarker(id: Int, marker: StrMarker): Future[Boolean] = {
    Future.successful(false)
  }

  override def deleteStrMarker(id: Int): Future[Boolean] = {
    Future.successful(false)
  }

  override def bulkCreateStrMarkers(markers: Seq[StrMarker]): Future[Seq[Int]] = {
    Future.failed(new UnsupportedOperationException("STR marker table has been replaced with str_mutation_rate"))
  }
}
