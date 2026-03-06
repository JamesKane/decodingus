package repositories

import jakarta.inject.{Inject, Singleton}
import models.dal.DatabaseSchema
import models.domain.genomics.{AlignmentMetadata, EmbeddedCoverage, MetricLevel}
import play.api.db.slick.DatabaseConfigProvider
import play.api.libs.json.Json

import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository interface for managing alignment metadata and coverage statistics.
 */
trait AlignmentRepository {
  def createMetadata(metadata: AlignmentMetadata): Future[AlignmentMetadata]

  def updateCoverage(metadataId: Long, coverage: EmbeddedCoverage): Future[Boolean]

  def findMetadataBySequenceFile(sequenceFileId: Long): Future[Seq[AlignmentMetadata]]

  def findMetadataByContig(genbankContigId: Int, metricLevel: Option[MetricLevel] = None): Future[Seq[AlignmentMetadata]]

  def findMetadataById(metadataId: Long): Future[Option[AlignmentMetadata]]

  def findAllBySequenceFile(sequenceFileId: Long): Future[Seq[AlignmentMetadata]]

  def deleteMetadata(metadataId: Long): Future[Int]

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

  override def createMetadata(metadata: AlignmentMetadata): Future[AlignmentMetadata] = {
    val insertQuery = (metadataTable returning metadataTable.map(_.id)
      into ((m, id) => m.copy(id = Some(id))))
      .+=(metadata)

    db.run(insertQuery.transactionally)
  }

  override def updateCoverage(metadataId: Long, coverage: EmbeddedCoverage): Future[Boolean] = {
    db.run(
      metadataTable.filter(_.id === metadataId)
        .map(_.coverage)
        .update(Some(Json.toJson(coverage)))
    ).map(_ > 0)
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

  override def findMetadataById(metadataId: Long): Future[Option[AlignmentMetadata]] = {
    db.run(
      metadataTable
        .filter(_.id === metadataId)
        .result
        .headOption
    )
  }

  override def findAllBySequenceFile(sequenceFileId: Long): Future[Seq[AlignmentMetadata]] = {
    db.run(
      metadataTable
        .filter(_.sequenceFileId === sequenceFileId)
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
