package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.domain.genomics.{AlignmentCoverage, AlignmentMetadata, MetricLevel}
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository interface for managing alignment metadata and coverage statistics.
 */
trait AlignmentRepository {
  /**
   * Creates a new alignment metadata record.
   *
   * @param metadata The alignment metadata to create
   * @return A future containing the created metadata with its assigned ID
   */
  def createMetadata(metadata: AlignmentMetadata): Future[AlignmentMetadata]

  /**
   * Creates or updates coverage statistics for an alignment.
   *
   * @param coverage The coverage statistics to upsert
   * @return A future containing the upserted coverage record
   */
  def upsertCoverage(coverage: AlignmentCoverage): Future[AlignmentCoverage]

  /**
   * Finds all alignment metadata for a specific sequence file.
   *
   * @param sequenceFileId The sequence file ID
   * @return A future containing a sequence of alignment metadata records
   */
  def findMetadataBySequenceFile(sequenceFileId: Long): Future[Seq[AlignmentMetadata]]

  /**
   * Finds alignment metadata for a specific contig.
   *
   * @param genbankContigId The GenBank contig ID
   * @param metricLevel     Optional filter by metric level
   * @return A future containing a sequence of alignment metadata records
   */
  def findMetadataByContig(genbankContigId: Int, metricLevel: Option[MetricLevel] = None): Future[Seq[AlignmentMetadata]]

  /**
   * Retrieves alignment metadata with associated coverage statistics.
   *
   * @param metadataId The alignment metadata ID
   * @return A future containing an optional tuple of (metadata, coverage)
   */
  def findMetadataWithCoverage(metadataId: Long): Future[Option[(AlignmentMetadata, Option[AlignmentCoverage])]]

  /**
   * Finds all alignment metadata and coverage for a sequence file.
   *
   * @param sequenceFileId The sequence file ID
   * @return A future containing a sequence of tuples (metadata, coverage)
   */
  def findAllWithCoverageBySequenceFile(sequenceFileId: Long): Future[Seq[(AlignmentMetadata, Option[AlignmentCoverage])]]

  /**
   * Deletes alignment metadata and associated coverage by ID.
   *
   * @param metadataId The alignment metadata ID to delete
   * @return A future containing the number of deleted records
   */
  def deleteMetadata(metadataId: Long): Future[Int]

  /**
   * Finds regional alignment statistics for a specific genomic region.
   *
   * @param genbankContigId The GenBank contig ID
   * @param startPos        Start position (1-based, inclusive)
   * @param endPos          End position (1-based, inclusive)
   * @return A future containing a sequence of regional alignment metadata
   */
  def findRegionalMetadata(genbankContigId: Int, startPos: Long, endPos: Long): Future[Seq[AlignmentMetadata]]
}

@Singleton
class AlignmentRepositoryImpl @Inject()(
                                         override protected val dbConfigProvider: DatabaseConfigProvider
                                       )(implicit override protected val ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with AlignmentRepository {

  import models.dal.MyPostgresProfile.api.*

  private val metadataTable = DatabaseSchema.domain.genomics.alignmentMetadata
  private val coverageTable = DatabaseSchema.domain.genomics.alignmentCoverages

  override def createMetadata(metadata: AlignmentMetadata): Future[AlignmentMetadata] = {
    val insertQuery = (metadataTable returning metadataTable.map(_.id)
      into ((m, id) => m.copy(id = Some(id))))
      .+=(metadata)

    db.run(insertQuery.transactionally)
  }

  override def upsertCoverage(coverage: AlignmentCoverage): Future[AlignmentCoverage] = {
    db.run(
      coverageTable.insertOrUpdate(coverage)
        .map(_ => coverage)
        .transactionally
    )
  }

  override def findMetadataBySequenceFile(sequenceFileId: Long): Future[Seq[AlignmentMetadata]] = {
    db.run(
      metadataTable
        .filter(_.sequenceFileId === sequenceFileId)
        .result
    )
  }

  override def findMetadataByContig(genbankContigId: Int, metricLevel: Option[MetricLevel] = None): Future[Seq[AlignmentMetadata]] = {
    val baseQuery = metadataTable.filter(_.genbankContigId === genbankContigId)
    val filteredQuery = metricLevel match {
      case Some(level) => baseQuery.filter(_.metricLevel === level)
      case None => baseQuery
    }

    db.run(filteredQuery.result)
  }

  override def findMetadataWithCoverage(metadataId: Long): Future[Option[(AlignmentMetadata, Option[AlignmentCoverage])]] = {
    db.run(
      metadataTable
        .filter(_.id === metadataId)
        .joinLeft(coverageTable)
        .on(_.id === _.alignmentMetadataId)
        .result
        .headOption
    )
  }

  override def findAllWithCoverageBySequenceFile(sequenceFileId: Long): Future[Seq[(AlignmentMetadata, Option[AlignmentCoverage])]] = {
    db.run(
      metadataTable
        .filter(_.sequenceFileId === sequenceFileId)
        .joinLeft(coverageTable)
        .on(_.id === _.alignmentMetadataId)
        .result
    )
  }

  override def deleteMetadata(metadataId: Long): Future[Int] = {
    db.run(
      metadataTable
        .filter(_.id === metadataId)
        .delete
    )
  }

  override def findRegionalMetadata(genbankContigId: Int, startPos: Long, endPos: Long): Future[Seq[AlignmentMetadata]] = {
    db.run(
      metadataTable
        .filter(m =>
          m.genbankContigId === genbankContigId &&
            m.metricLevel === MetricLevel.REGION &&
            m.regionStartPos.isDefined &&
            m.regionEndPos.isDefined &&
            m.regionStartPos <= endPos &&
            m.regionEndPos >= startPos
        )
        .result
    )
  }
}