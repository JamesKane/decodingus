package services.genomics

import jakarta.inject.{Inject, Singleton}
import models.api.{PendingProposalSummary, SequencerLabLookupResponse}
import models.api.genomics.AssociateLabWithInstrumentResponse
import repositories.{InstrumentObservationRepository, InstrumentProposalRepository, SequencerInstrumentRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SequencerInstrumentService @Inject()(
                                            instrumentRepository: SequencerInstrumentRepository,
                                            proposalRepository: InstrumentProposalRepository,
                                            observationRepository: InstrumentObservationRepository
                                          )(implicit ec: ExecutionContext) {

  def getAllLabInstrumentAssociations: Future[Seq[models.api.SequencerLabInfo]] = {
    instrumentRepository.findAllLabInstrumentAssociations()
  }

  def lookupLab(instrumentId: String): Future[Option[SequencerLabLookupResponse]] = {
    for {
      confirmedOpt <- instrumentRepository.findLabByInstrumentId(instrumentId)
      proposalOpt <- proposalRepository.findActiveByInstrumentId(instrumentId)
      obsCount <- observationRepository.findByInstrumentId(instrumentId).map(_.size)
    } yield {
      confirmedOpt match {
        case Some(labInfo) =>
          val pendingSummary = proposalOpt.flatMap { p =>
            if (p.proposedLabName != labInfo.labName) {
              Some(PendingProposalSummary(
                proposalId = p.id.getOrElse(0),
                proposedLabName = p.proposedLabName,
                observationCount = p.observationCount,
                confidenceScore = p.confidenceScore,
                status = p.status.dbValue
              ))
            } else None
          }
          Some(SequencerLabLookupResponse(
            instrumentId = labInfo.instrumentId,
            labName = Some(labInfo.labName),
            isD2c = Some(labInfo.isD2c),
            manufacturer = labInfo.manufacturer,
            model = labInfo.model,
            websiteUrl = labInfo.websiteUrl,
            source = "CURATOR",
            confidenceScore = 1.0,
            observationCount = obsCount,
            pendingProposal = pendingSummary
          ))
        case None =>
          proposalOpt.map { proposal =>
            SequencerLabLookupResponse(
              instrumentId = instrumentId,
              labName = Some(proposal.proposedLabName),
              manufacturer = proposal.proposedManufacturer,
              model = proposal.proposedModel,
              source = "CONSENSUS",
              confidenceScore = proposal.confidenceScore,
              observationCount = proposal.observationCount,
              pendingProposal = Some(PendingProposalSummary(
                proposalId = proposal.id.getOrElse(0),
                proposedLabName = proposal.proposedLabName,
                observationCount = proposal.observationCount,
                confidenceScore = proposal.confidenceScore,
                status = proposal.status.dbValue
              ))
            )
          }
      }
    }
  }

  def associateLabWithInstrument(
                                  instrumentId: String,
                                  labName: String,
                                  manufacturer: Option[String] = None,
                                  model: Option[String] = None
                                ): Future[AssociateLabWithInstrumentResponse] = {
    if (instrumentId.isBlank) {
      Future.failed(new IllegalArgumentException("Instrument ID cannot be empty"))
    } else if (labName.isBlank) {
      Future.failed(new IllegalArgumentException("Lab name cannot be empty"))
    } else {
      instrumentRepository.associateLabWithInstrument(instrumentId, labName, manufacturer, model)
    }
  }
}
