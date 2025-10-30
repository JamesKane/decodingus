package repositories

import jakarta.inject.{Inject, Singleton}
import models.api.SequencerLabInfo
import models.dal.DatabaseSchema
import play.api.db.slick.DatabaseConfigProvider

import scala.concurrent.{ExecutionContext, Future}

/**
 * Repository interface for managing sequencer instrument data.
 */
trait SequencerInstrumentRepository {
  /**
   * Retrieves lab information for a given instrument ID.
   *
   * @param instrumentId the unique instrument ID from BAM/CRAM headers
   * @return a future containing optional lab information if the instrument is found
   */
  def findLabByInstrumentId(instrumentId: String): Future[Option[SequencerLabInfo]]
}

@Singleton
class SequencerInstrumentRepositoryImpl @Inject()(
                                                   override protected val dbConfigProvider: DatabaseConfigProvider
                                                 )(implicit override protected val ec: ExecutionContext)
  extends BaseRepository(dbConfigProvider)
    with SequencerInstrumentRepository {

  import models.dal.MyPostgresProfile.api.*

  private val instrumentsTable = DatabaseSchema.domain.genomics.sequencerInstruments
  private val labsTable = DatabaseSchema.domain.genomics.sequencingLabs

  override def findLabByInstrumentId(instrumentId: String): Future[Option[SequencerLabInfo]] = {
    val query = instrumentsTable
      .filter(_.instrumentId === instrumentId)
      .join(labsTable).on(_.labId === _.id)
      .map { case (instrument, lab) =>
        (
          instrument.instrumentId,
          lab.name,
          lab.isD2c,
          instrument.manufacturer,
          instrument.model,
          lab.websiteUrl
        )
      }

    db.run(query.result.headOption).map {
      case Some((instId, labName, isD2c, manufacturer, model, websiteUrl)) =>
        Some(SequencerLabInfo(
          instrumentId = instId,
          labName = labName,
          isD2c = isD2c,
          manufacturer = manufacturer,
          model = model,
          websiteUrl = websiteUrl
        ))
      case None => None
    }
  }
}