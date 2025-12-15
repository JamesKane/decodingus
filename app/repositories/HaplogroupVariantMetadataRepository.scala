package repositories

import jakarta.inject.Inject
import models.domain.haplogroups.{HaplogroupVariant, HaplogroupVariantMetadata}
import play.api.db.slick.DatabaseConfigProvider

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

/**
 * A repository trait for managing metadata associated with haplogroup variant revisions.
 * Provides methods for creating, retrieving, updating, and querying haplogroup variant metadata.
 */
trait HaplogroupVariantMetadataRepository {
  /**
   * Adds metadata for a haplogroup variant revision to the repository.
   *
   * @param metadata An instance of HaplogroupVariantMetadata containing details such as
   *                 variant ID, revision ID, author, timestamp, comment, change type,
   *                 and optional previous revision ID.
   * @return A Future containing the number of rows affected by the addition, typically 1 if successful.
   */
  def addVariantRevisionMetadata(metadata: HaplogroupVariantMetadata): Future[Int]

  /**
   * Retrieves the metadata for a specific revision of a haplogroup variant.
   *
   * @param variantId  The unique identifier of the haplogroup variant.
   * @param revisionId The unique identifier of the specific revision of the haplogroup variant.
   * @return A Future containing an Option with the metadata for the specified variant revision.
   *         If no metadata is found, the Option will be None.
   */
  def getVariantRevisionMetadata(variantId: Int, revisionId: Int): Future[Option[HaplogroupVariantMetadata]]

  /**
   * Retrieves the revision history for a specific haplogroup variant.
   *
   * @param variantId The unique identifier of the haplogroup variant whose revision history is being requested.
   * @return A Future containing a sequence of tuples, where each tuple consists of a HaplogroupVariant and its associated HaplogroupVariantMetadata.
   *         The sequence represents the revision history for the specified haplogroup variant.
   */
  def getVariantRevisionHistory(variantId: Int): Future[Seq[(HaplogroupVariant, HaplogroupVariantMetadata)]]

  /**
   * Retrieves all haplogroup variant metadata revisions created by a specific author.
   *
   * @param author The name of the author whose revisions need to be retrieved.
   * @return A Future containing a sequence of HaplogroupVariantMetadata instances
   *         representing the revisions authored by the specified individual.
   */
  def getVariantRevisionsByAuthor(author: String): Future[Seq[HaplogroupVariantMetadata]]

  /**
   * Retrieves a sequence of haplogroup variant metadata revisions that were created within the specified date range.
   *
   * @param startDate The start date and time of the range within which revisions should be retrieved.
   * @param endDate   The end date and time of the range within which revisions should be retrieved.
   * @return A Future containing a sequence of HaplogroupVariantMetadata instances representing the revisions
   *         created within the specified date range.
   */
  def getVariantRevisionsBetweenDates(startDate: LocalDateTime, endDate: LocalDateTime): Future[Seq[HaplogroupVariantMetadata]]

  /**
   * Updates the comment for a specific revision of a haplogroup variant.
   *
   * @param variantId  The unique identifier of the haplogroup variant.
   * @param revisionId The unique identifier of the revision whose comment is to be updated.
   * @param newComment The new comment to be associated with the specified revision.
   * @return A Future containing the number of rows affected by the update operation, typically 1 if successful.
   */
  def updateVariantRevisionComment(variantId: Int, revisionId: Int, newComment: String): Future[Int]

  /**
   * Retrieves the latest haplogroup variant metadata revisions for a specific change type.
   *
   * @param changeType The type of change (e.g., "CREATE", "UPDATE", "DELETE") used to filter the revisions.
   * @param limit      The maximum number of revisions to retrieve. Default is 10.
   * @return A Future containing a sequence of HaplogroupVariantMetadata instances representing the latest revisions
   *         that match the specified change type, up to the specified limit.
   */
  def getLatestVariantRevisionsByChangeType(changeType: String, limit: Int = 10): Future[Seq[HaplogroupVariantMetadata]]

  /**
   * Retrieves the revision chain for a specific variant revision, starting from the given revision ID
   * and following the chain of previous revisions until the first revision is reached.
   *
   * @param variantId  The unique identifier of the haplogroup variant.
   * @param revisionId The unique identifier of the revision to start the chain from.
   * @return A Future containing a sequence of HaplogroupVariantMetadata instances, representing the
   *         revision chain of the specified variant starting from the specified revision.
   */
  def getVariantRevisionChain(variantId: Int, revisionId: Int): Future[Seq[HaplogroupVariantMetadata]]

  /**
   * Retrieves the latest revision ID for a given haplogroup variant ID.
   *
   * @param haplogroupVariantId The unique identifier of the haplogroup variant.
   * @return A Future containing an Option with the latest revision ID, or None if no revisions exist.
   */
  def getLatestRevisionId(haplogroupVariantId: Int): Future[Option[Int]]
}

class HaplogroupVariantMetadataRepositoryImpl @Inject()(
                                                         dbConfigProvider: DatabaseConfigProvider
                                                       )(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with HaplogroupVariantMetadataRepository {

  import models.dal.DatabaseSchema.*
  import models.dal.DatabaseSchema.domain.haplogroups.{haplogroupVariantMetadata, haplogroupVariants}
  import models.dal.MyPostgresProfile.api.*

  override def addVariantRevisionMetadata(metadata: HaplogroupVariantMetadata): Future[Int] = {
    val insertion = haplogroupVariantMetadata += metadata
    runQuery(insertion)
  }

  override def getVariantRevisionMetadata(variantId: Int, revisionId: Int): Future[Option[HaplogroupVariantMetadata]] = {
    val query = haplogroupVariantMetadata
      .filter(m => m.haplogroup_variant_id === variantId && m.revision_id === revisionId)
      .result
      .headOption

    runQuery(query)
  }

  override def getVariantRevisionHistory(variantId: Int): Future[Seq[(HaplogroupVariant, HaplogroupVariantMetadata)]] = {
    val query = for {
      variant <- haplogroupVariants if variant.haplogroupVariantId === variantId
      metadata <- haplogroupVariantMetadata if metadata.haplogroup_variant_id === variant.haplogroupVariantId
    } yield (variant, metadata)

    runQuery(query.sortBy(_._2.timestamp.desc).result)
  }

  override def getVariantRevisionsByAuthor(author: String): Future[Seq[HaplogroupVariantMetadata]] = {
    val query = haplogroupVariantMetadata
      .filter(_.author === author)
      .sortBy(_.timestamp.desc)
      .result

    runQuery(query)
  }

  override def getVariantRevisionsBetweenDates(
                                                startDate: LocalDateTime,
                                                endDate: LocalDateTime
                                              ): Future[Seq[HaplogroupVariantMetadata]] = {
    val query = haplogroupVariantMetadata
      .filter(m => m.timestamp >= startDate && m.timestamp <= endDate)
      .sortBy(_.timestamp.desc)
      .result

    runQuery(query)
  }

  override def updateVariantRevisionComment(
                                             variantId: Int,
                                             revisionId: Int,
                                             newComment: String
                                           ): Future[Int] = {
    val query = haplogroupVariantMetadata
      .filter(m => m.haplogroup_variant_id === variantId && m.revision_id === revisionId)
      .map(_.comment)
      .update(newComment)

    runQuery(query)
  }

  override def getLatestVariantRevisionsByChangeType(
                                                      changeType: String,
                                                      limit: Int = 10
                                                    ): Future[Seq[HaplogroupVariantMetadata]] = {
    val query = haplogroupVariantMetadata
      .filter(_.change_type === changeType)
      .sortBy(_.timestamp.desc)
      .take(limit)
      .result

    runQuery(query)
  }

  override def getVariantRevisionChain(
                                        variantId: Int,
                                        revisionId: Int
                                      ): Future[Seq[HaplogroupVariantMetadata]] = {
    def recursiveChain(
                        currentRevisionId: Int,
                        chain: Seq[HaplogroupVariantMetadata] = Seq.empty
                      ): DBIO[Seq[HaplogroupVariantMetadata]] = {
      val query = haplogroupVariantMetadata
        .filter(m =>
          m.haplogroup_variant_id === variantId &&
            m.revision_id === currentRevisionId
        )
        .result.headOption

      query.flatMap {
        case Some(metadata) =>
          metadata.previous_revision_id match {
            case Some(prevId) => recursiveChain(prevId, chain :+ metadata)
            case None => DBIO.successful(chain :+ metadata)
          }
        case None => DBIO.successful(chain)
      }
    }

    runQuery(recursiveChain(revisionId))
  }

  override def getLatestRevisionId(haplogroupVariantId: Int): Future[Option[Int]] = {
    val query = haplogroupVariantMetadata
      .filter(_.haplogroup_variant_id === haplogroupVariantId)
      .map(_.revision_id)
      .max
      .result

    runQuery(query)
  }
}