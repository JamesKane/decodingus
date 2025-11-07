package repositories

import jakarta.inject.{Inject, Singleton}
import models.api.SequencerLabInfo
import models.api.genomics.AssociateLabWithInstrumentResponse
import models.dal.DatabaseSchema
import models.domain.genomics.SequencingLab
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

  /**
   * Retrieves all lab-instrument associations.
   *
   * @return a future containing a list of all lab-instrument associations
   */
  def findAllLabInstrumentAssociations(): Future[Seq[SequencerLabInfo]]
  
  /**
   * Associates a lab with an instrument ID.
   * If the lab doesn't exist, creates a placeholder record.
   *
   * @param instrumentId the unique instrument ID from BAM/CRAM headers
   * @param labName      the name of the lab to associate
   * @return a future containing the association response
   */
  def associateLabWithInstrument(instrumentId: String, labName: String): Future[AssociateLabWithInstrumentResponse]
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

  override def findAllLabInstrumentAssociations(): Future[Seq[SequencerLabInfo]] = {
    val query = instrumentsTable
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
      .sortBy(_._1) // Sort by instrumentId for consistency

    db.run(query.result).map { results =>
      results.map { case (instId, labName, isD2c, manufacturer, model, websiteUrl) =>
        SequencerLabInfo(
          instrumentId = instId,
          labName = labName,
          isD2c = isD2c,
          manufacturer = manufacturer,
          model = model,
          websiteUrl = websiteUrl
        )
      }
    }
  }

  override def associateLabWithInstrument(instrumentId: String, labName: String): Future[AssociateLabWithInstrumentResponse] = {
    db.run(
      (for {
        // Check if lab already exists
        existingLab <- labsTable.filter(_.name === labName).result.headOption

        labId <- if (existingLab.isDefined) {
          // Lab exists, use it
          DBIO.successful(existingLab.get.id.get)
        } else {
          // Create placeholder lab
          val newLab = SequencingLab(name = labName)
          (labsTable returning labsTable.map(_.id)) += newLab
        }

        // Update instrument with lab ID
        _ <- instrumentsTable.filter(_.instrumentId === instrumentId).map(_.labId).update(labId)

      } yield (labId, existingLab.isDefined)).transactionally
    ).map { case (labId, labExists) =>
      AssociateLabWithInstrumentResponse(
        instrumentId = instrumentId,
        labId = labId,
        labName = labName,
        isNewLab = !labExists,
        message = if (labExists) {
          s"Lab '$labName' associated with instrument '$instrumentId'"
        } else {
          s"New lab placeholder '$labName' created and associated with instrument '$instrumentId'"
        }
      )
    }.recover {
      case e: Exception =>
        throw new Exception(s"Failed to associate lab with instrument: ${e.getMessage}", e)
    }
  }
}