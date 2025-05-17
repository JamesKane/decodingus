package repositories

import jakarta.inject.Inject
import models.{HaplogroupRelationship, RelationshipRevisionMetadata}
import play.api.db.slick.DatabaseConfigProvider

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

trait HaplogroupRevisionMetadataRepository {
  def addRelationshipRevisionMetadata(metadata: RelationshipRevisionMetadata): Future[Int]

  def getRelationshipRevisionMetadata(relationshipId: Int, revisionId: Int): Future[Option[RelationshipRevisionMetadata]]

  def getRelationshipRevisionHistory(relationshipId: Int): Future[Seq[(HaplogroupRelationship, RelationshipRevisionMetadata)]]

  def getRevisionsByAuthor(author: String): Future[Seq[RelationshipRevisionMetadata]]

  def getRevisionsBetweenDates(startDate: LocalDateTime, endDate: LocalDateTime): Future[Seq[RelationshipRevisionMetadata]]

  def updateRevisionComment(relationshipId: Int, revisionId: Int, newComment: String): Future[Int]

  def getLatestRevisionsByChangeType(changeType: String, limit: Int): Future[Seq[RelationshipRevisionMetadata]]

  def getRevisionChain(relationshipId: Int, revisionId: Int): Future[Seq[RelationshipRevisionMetadata]]
}

class HaplogroupRevisionMetadataRepositoryImpl @Inject()(
                                                          dbConfigProvider: DatabaseConfigProvider
                                                        )(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with HaplogroupRevisionMetadataRepository {

  import models.dal.DatabaseSchema.*
  import models.dal.MyPostgresProfile.api.*

  override def addRelationshipRevisionMetadata(metadata: RelationshipRevisionMetadata): Future[Int] = {
    runQuery(relationshipRevisionMetadata += metadata)
  }

  override def getRelationshipRevisionMetadata(relationshipId: Int, revisionId: Int): Future[Option[RelationshipRevisionMetadata]] = {
    val query = relationshipRevisionMetadata
      .filter(m => m.haplogroup_relationship_id === relationshipId && m.revisionId === revisionId)
      .result
      .headOption

    runQuery(query)
  }

  override def getRelationshipRevisionHistory(relationshipId: Int): Future[Seq[(HaplogroupRelationship, RelationshipRevisionMetadata)]] = {
    val query = for {
      rel <- haplogroupRelationships if rel.haplogroupRelationshipId === relationshipId
      metadata <- relationshipRevisionMetadata if metadata.haplogroup_relationship_id === rel.haplogroupRelationshipId
    } yield (rel, metadata)

    runQuery(query.sortBy(_._2.timestamp.desc).result)
  }

  override def getRevisionsByAuthor(author: String): Future[Seq[RelationshipRevisionMetadata]] = {
    val query = relationshipRevisionMetadata
      .filter(_.author === author)
      .sortBy(_.timestamp.desc)
      .result

    runQuery(query)
  }

  override def getRevisionsBetweenDates(startDate: LocalDateTime, endDate: LocalDateTime): Future[Seq[RelationshipRevisionMetadata]] = {
    val query = relationshipRevisionMetadata
      .filter(m => m.timestamp >= startDate && m.timestamp <= endDate)
      .sortBy(_.timestamp.desc)
      .result

    runQuery(query)
  }

  override def updateRevisionComment(relationshipId: Int, revisionId: Int, newComment: String): Future[Int] = {
    val query = relationshipRevisionMetadata
      .filter(m => m.haplogroup_relationship_id === relationshipId && m.revisionId === revisionId)
      .map(_.comment)
      .update(newComment)

    runQuery(query)
  }

  override def getLatestRevisionsByChangeType(changeType: String, limit: Int): Future[Seq[RelationshipRevisionMetadata]] = {
    {
      val query = relationshipRevisionMetadata
        .filter(_.changeType === changeType)
        .sortBy(_.timestamp.desc)
        .take(limit)
        .result

      runQuery(query)
    }
  }

  override def getRevisionChain(relationshipId: Int, revisionId: Int): Future[Seq[RelationshipRevisionMetadata]] = {
    def recursiveChain(
                        currentRevisionId: Int,
                        chain: Seq[RelationshipRevisionMetadata] = Seq.empty
                      ): DBIO[Seq[RelationshipRevisionMetadata]] = {
      val query = relationshipRevisionMetadata
        .filter(m =>
          m.haplogroup_relationship_id === relationshipId &&
            m.revisionId === currentRevisionId
        )
        .result.headOption

      query.flatMap {
        case Some(metadata) =>
          metadata.previousRevisionId match {
            case Some(prevId) => recursiveChain(prevId, chain :+ metadata)
            case None => DBIO.successful(chain :+ metadata)
          }
        case None => DBIO.successful(chain)
      }
    }

    runQuery(recursiveChain(revisionId))
  }
}
