package services.genomics

import jakarta.inject.{Inject, Singleton}
import models.api.genomics.AssociateLabWithInstrumentResponse
import repositories.SequencerInstrumentRepository

import scala.concurrent.Future

/**
 * Service for managing sequencer instrument operations.
 *
 * @param instrumentRepository the repository for accessing instrument data
 */
@Singleton
class SequencerInstrumentService @Inject()(
                                            instrumentRepository: SequencerInstrumentRepository
                                          ) {

  /**
   * Retrieves all lab-instrument associations.
   *
   * @return a future containing all associations
   */
  def getAllLabInstrumentAssociations: Future[Seq[models.api.SequencerLabInfo]] = {
    instrumentRepository.findAllLabInstrumentAssociations()
  }

  /**
   * Associates a lab with an instrument ID.
   * If the lab doesn't exist, a placeholder is created with the provided name.
   *
   * @param instrumentId the unique instrument ID
   * @param labName      the name of the lab to associate
   * @return a future containing the association response
   */
  def associateLabWithInstrument(instrumentId: String, labName: String): Future[AssociateLabWithInstrumentResponse] = {
    if (instrumentId.isBlank) {
      Future.failed(new IllegalArgumentException("Instrument ID cannot be empty"))
    } else if (labName.isBlank) {
      Future.failed(new IllegalArgumentException("Lab name cannot be empty"))
    } else {
      instrumentRepository.associateLabWithInstrument(instrumentId, labName)
    }
  }
}