package repositories

import jakarta.inject.Inject
import models.{HaplogroupRelationship, RelationshipRevisionMetadata}
import play.api.db.slick.DatabaseConfigProvider

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository trait responsible for managing haplogroup relationship revision metadata.
 * This provides methods for adding, retrieving, updating, and querying metadata related to revisions
 * of haplogroup relationships.
 */
trait HaplogroupRevisionMetadataRepository {
  /**
   * Adds a new relationship revision metadata entry to the repository.
   *
   * @param metadata The relationship revision metadata containing details such as
   *                 the haplogroup relationship ID, revision ID, author, timestamp,
   *                 comment, change type, and an optional previous revision ID.
   * @return A future containing the ID of the newly added metadata entry.
   */
  def addRelationshipRevisionMetadata(metadata: RelationshipRevisionMetadata): Future[Int]

  /**
   * Retrieves the revision metadata for a specific haplogroup relationship revision.
   *
   * @param relationshipId The unique identifier of the haplogroup relationship.
   * @param revisionId     The unique identifier of the revision for which metadata is to be retrieved.
   * @return A future containing an optional `RelationshipRevisionMetadata` object. If the specified
   *         relationship and revision IDs are found, the metadata is returned, otherwise `None`.
   */
  def getRelationshipRevisionMetadata(relationshipId: Int, revisionId: Int): Future[Option[RelationshipRevisionMetadata]]

  /**
   * Retrieves the revision history for a specific haplogroup relationship.
   *
   * @param relationshipId The unique identifier of the haplogroup relationship whose revision history is to be retrieved.
   * @return A future containing a sequence of tuples, where each tuple consists of a `HaplogroupRelationship` object
   *         and its associated `RelationshipRevisionMetadata`.
   */
  def getRelationshipRevisionHistory(relationshipId: Int): Future[Seq[(HaplogroupRelationship, RelationshipRevisionMetadata)]]

  /**
   * Retrieves all relationship revisions authored by the specified author.
   *
   * @param author The name of the author whose revisions are to be retrieved.
   * @return A future containing a sequence of `RelationshipRevisionMetadata` objects associated with the specified author.
   */
  def getRevisionsByAuthor(author: String): Future[Seq[RelationshipRevisionMetadata]]

  /**
   * Retrieves the relationship revision metadata entries that fall within the specified date range.
   *
   * @param startDate The start date and time of the range (inclusive).
   * @param endDate   The end date and time of the range (inclusive).
   * @return A future containing a sequence of `RelationshipRevisionMetadata` objects that match the specified date range.
   */
  def getRevisionsBetweenDates(startDate: LocalDateTime, endDate: LocalDateTime): Future[Seq[RelationshipRevisionMetadata]]

  /**
   * Updates the comment associated with a specific haplogroup relationship revision.
   *
   * @param relationshipId The unique identifier of the haplogroup relationship.
   * @param revisionId     The unique identifier of the revision.
   * @param newComment     The new comment text to be updated.
   * @return A future containing the number of rows updated in the database.
   */
  def updateRevisionComment(relationshipId: Int, revisionId: Int, newComment: String): Future[Int]

  /**
   * Retrieves the latest relationship revision metadata entries filtered by the specified change type.
   * The number of entries returned is determined by the specified limit.
   *
   * @param changeType The type of change to filter the revision metadata entries by.
   * @param limit      The maximum number of revision metadata entries to retrieve.
   * @return A future containing a sequence of `RelationshipRevisionMetadata` objects that match the specified change type.
   */
  def getLatestRevisionsByChangeType(changeType: String, limit: Int): Future[Seq[RelationshipRevisionMetadata]]

  /**
   * Retrieves a chain of revisions for a specific haplogroup relationship starting from the specified revision.
   *
   * @param relationshipId The unique identifier of the haplogroup relationship whose revision chain is to be retrieved.
   * @param revisionId     The unique identifier of the starting revision for which the chain is to be retrieved.
   * @return A future containing a sequence of `RelationshipRevisionMetadata` representing the chain of revisions,
   *         starting from the specified revision and including all subsequent revisions.
   */
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
