package repositories

import jakarta.inject.Inject
import models.{HaplogroupVariant, HaplogroupVariantMetadata}
import play.api.db.slick.DatabaseConfigProvider

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

trait HaplogroupVariantMetadataRepository {
  def addVariantRevisionMetadata(metadata: HaplogroupVariantMetadata): Future[Int]

  def getVariantRevisionMetadata(variantId: Int, revisionId: Int): Future[Option[HaplogroupVariantMetadata]]

  def getVariantRevisionHistory(variantId: Int): Future[Seq[(HaplogroupVariant, HaplogroupVariantMetadata)]]

  def getVariantRevisionsByAuthor(author: String): Future[Seq[HaplogroupVariantMetadata]]

  def getVariantRevisionsBetweenDates(startDate: LocalDateTime, endDate: LocalDateTime): Future[Seq[HaplogroupVariantMetadata]]

  def updateVariantRevisionComment(variantId: Int, revisionId: Int, newComment: String): Future[Int]

  def getLatestVariantRevisionsByChangeType(changeType: String, limit: Int = 10): Future[Seq[HaplogroupVariantMetadata]]

  def getVariantRevisionChain(variantId: Int, revisionId: Int): Future[Seq[HaplogroupVariantMetadata]]
}

class HaplogroupVariantMetadataRepositoryImpl @Inject()(
                                                         dbConfigProvider: DatabaseConfigProvider
                                                       )(implicit ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with HaplogroupVariantMetadataRepository {

  import models.dal.DatabaseSchema.*
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
}