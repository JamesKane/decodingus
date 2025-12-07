package services.firehose

import com.vividsolutions.jts.geom.Point
import jakarta.inject.{Inject, Singleton}
import models.api.{ExternalBiosampleRequest, PublicationInfo}
import models.domain.genomics.{BiosampleType, CitizenBiosample, SpecimenDonor}
import models.domain.publications.{CitizenBiosampleOriginalHaplogroup, Publication, PublicationCitizenBiosample}
import play.api.Logging
import repositories.*
import services.{BiosampleDataService, CoordinateValidation}

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/**
 * Handles CitizenBiosampleEvent processing in an event-driven manner.
 *
 * This handler can be invoked from:
 * - REST API controller (Phase 1)
 * - Kafka consumer (Phase 2)
 * - AT Protocol Firehose consumer (Phase 3)
 *
 * The handler is stateless and processes each event independently,
 * returning a FirehoseResult that can be translated to HTTP responses,
 * Kafka acknowledgments, or Firehose cursor updates as appropriate.
 */
@Singleton
class CitizenBiosampleEventHandler @Inject()(
                                              citizenBiosampleRepository: CitizenBiosampleRepository,
                                              biosampleDataService: BiosampleDataService,
                                              publicationRepository: PublicationRepository,
                                              publicationCitizenBiosampleRepository: PublicationCitizenBiosampleRepository,
                                              citizenBiosampleOriginalHaplogroupRepository: CitizenBiosampleOriginalHaplogroupRepository,
                                              specimenDonorRepository: SpecimenDonorRepository
                                            )(implicit ec: ExecutionContext) extends CoordinateValidation with Logging {

  /**
   * Process a CitizenBiosampleEvent and return a result.
   * This is the main entry point for event processing.
   */
  def handle(event: CitizenBiosampleEvent): Future[FirehoseResult] = {
    logger.debug(s"Processing ${event.action} event for atUri: ${event.atUri}")

    event.action match {
      case FirehoseAction.Create => handleCreate(event)
      case FirehoseAction.Update => handleUpdate(event)
      case FirehoseAction.Delete => handleDelete(event)
    }
  }

  private def handleCreate(event: CitizenBiosampleEvent): Future[FirehoseResult] = {
    event.payload match {
      case None =>
        Future.successful(FirehoseResult.ValidationError(event.atUri, "Payload required for create"))

      case Some(request) =>
        (for {
          geocoord <- validateCoordinates(request.latitude, request.longitude)
          existing <- citizenBiosampleRepository.findByAccession(request.sampleAccession)
          result <- existing match {
            case Some(_) =>
              Future.successful(FirehoseResult.Conflict(event.atUri,
                s"Biosample with accession ${request.sampleAccession} already exists"))

            case None =>
              createBiosample(event.atUri, request, geocoord)
          }
        } yield result).recover {
          case e: IllegalArgumentException =>
            FirehoseResult.ValidationError(event.atUri, e.getMessage)
          case e: Exception =>
            logger.error(s"Error processing create event for ${event.atUri}", e)
            FirehoseResult.Error(event.atUri, e.getMessage, Some(e))
        }
    }
  }

  private def handleUpdate(event: CitizenBiosampleEvent): Future[FirehoseResult] = {
    event.payload match {
      case None =>
        Future.successful(FirehoseResult.ValidationError(event.atUri, "Payload required for update"))

      case Some(request) =>
        (for {
          geocoord <- validateCoordinates(request.latitude, request.longitude)
          existing <- citizenBiosampleRepository.findByAtUri(event.atUri)
          result <- existing match {
            case None =>
              Future.successful(FirehoseResult.NotFound(event.atUri))

            case Some(biosample) if event.atCid.isDefined && event.atCid != biosample.atCid =>
              Future.successful(FirehoseResult.Conflict(event.atUri,
                s"Optimistic locking failure: expected ${biosample.atCid}, got ${event.atCid}"))

            case Some(biosample) =>
              updateBiosample(biosample, request, geocoord)
          }
        } yield result).recover {
          case e: IllegalArgumentException =>
            FirehoseResult.ValidationError(event.atUri, e.getMessage)
          case e: Exception =>
            logger.error(s"Error processing update event for ${event.atUri}", e)
            FirehoseResult.Error(event.atUri, e.getMessage, Some(e))
        }
    }
  }

  private def handleDelete(event: CitizenBiosampleEvent): Future[FirehoseResult] = {
    citizenBiosampleRepository.softDeleteByAtUri(event.atUri).map {
      case true => FirehoseResult.Success(event.atUri, "", None, "Deleted")
      case false => FirehoseResult.NotFound(event.atUri)
    }.recover {
      case e: Exception =>
        logger.error(s"Error processing delete event for ${event.atUri}", e)
        FirehoseResult.Error(event.atUri, e.getMessage, Some(e))
    }
  }

  private def createBiosample(
                               atUri: String,
                               request: ExternalBiosampleRequest,
                               geocoord: Option[Point]
                             ): Future[FirehoseResult] = {
    for {
      donorId <- resolveOrCreateDonor(request, geocoord)
      sampleGuid = UUID.randomUUID()
      newAtCid = UUID.randomUUID().toString

      citizenBiosample = CitizenBiosample(
        id = None,
        atUri = Some(atUri),
        accession = Some(request.sampleAccession),
        alias = request.alias,
        sourcePlatform = Some(request.sourceSystem),
        collectionDate = None,
        sex = request.sex,
        geocoord = geocoord,
        description = Some(request.description),
        yHaplogroup = request.haplogroups.flatMap(_.yDna),
        mtHaplogroup = request.haplogroups.flatMap(_.mtDna),
        sampleGuid = sampleGuid,
        deleted = false,
        atCid = Some(newAtCid),
        createdAt = LocalDateTime.now(),
        updatedAt = LocalDateTime.now(),
        specimenDonorId = donorId
      )

      created <- citizenBiosampleRepository.create(citizenBiosample)
      _ <- handleDataAssociation(created.sampleGuid, request, isUpdate = false)
    } yield FirehoseResult.Success(atUri, newAtCid, Some(created.sampleGuid), "Created")
  }

  private def updateBiosample(
                               existing: CitizenBiosample,
                               request: ExternalBiosampleRequest,
                               geocoord: Option[Point]
                             ): Future[FirehoseResult] = {
    for {
      donorId <- if (request.donorIdentifier.isDefined) {
        resolveOrCreateDonor(request, geocoord)
      } else {
        Future.successful(existing.specimenDonorId)
      }

      newAtCid = UUID.randomUUID().toString
      toUpdate = existing.copy(
        description = Some(request.description),
        alias = request.alias,
        sourcePlatform = Some(request.sourceSystem),
        sex = request.sex,
        geocoord = geocoord,
        atUri = request.atUri,
        accession = Some(request.sampleAccession),
        yHaplogroup = request.haplogroups.flatMap(_.yDna).orElse(existing.yHaplogroup),
        mtHaplogroup = request.haplogroups.flatMap(_.mtDna).orElse(existing.mtHaplogroup),
        atCid = Some(newAtCid),
        updatedAt = LocalDateTime.now(),
        specimenDonorId = donorId
      )

      success <- citizenBiosampleRepository.update(toUpdate, request.atCid)
      _ <- if (success) {
        handleDataAssociation(existing.sampleGuid, request, isUpdate = true)
      } else {
        Future.failed(new RuntimeException("Update failed"))
      }
    } yield FirehoseResult.Success(existing.atUri.getOrElse(""), newAtCid, Some(existing.sampleGuid), "Updated")
  }

  // --- Helper methods (moved from CitizenBiosampleService) ---

  private def extractDidFromAtUri(atUri: String): Option[String] = {
    if (atUri.startsWith("at://")) {
      val withoutPrefix = atUri.stripPrefix("at://")
      val didEnd = withoutPrefix.indexOf('/')
      if (didEnd > 0) Some(withoutPrefix.substring(0, didEnd))
      else Some(withoutPrefix)
    } else None
  }

  private def resolveOrCreateDonor(
                                    request: ExternalBiosampleRequest,
                                    geocoord: Option[Point]
                                  ): Future[Option[Int]] = {
    val citizenDid = request.citizenDid.orElse(request.atUri.flatMap(extractDidFromAtUri))

    (citizenDid, request.donorIdentifier) match {
      case (Some(did), Some(identifier)) =>
        specimenDonorRepository.findByDidAndIdentifier(did, identifier).flatMap {
          case Some(existingDonor) =>
            Future.successful(existingDonor.id)
          case None =>
            val newDonor = SpecimenDonor(
              donorIdentifier = identifier,
              originBiobank = request.centerName,
              donorType = request.donorType.getOrElse(BiosampleType.Citizen),
              sex = request.sex,
              geocoord = geocoord,
              pgpParticipantId = None,
              atUri = Some(did),
              dateRangeStart = None,
              dateRangeEnd = None
            )
            specimenDonorRepository.create(newDonor).map(_.id)
        }
      case _ => Future.successful(None)
    }
  }

  private def handleDataAssociation(
                                     guid: UUID,
                                     request: ExternalBiosampleRequest,
                                     isUpdate: Boolean
                                   ): Future[Unit] = {
    val publicationFuture = request.publication
      .map(pub => linkPublication(guid, pub)
        .recoverWith { case e =>
          Future.failed(new RuntimeException(s"Publication linkage failed: ${e.getMessage}", e))
        })
      .getOrElse(Future.successful(()))

    val sequenceDataFuture = if (isUpdate) {
      biosampleDataService.replaceSequenceData(guid, request.sequenceData)
    } else {
      biosampleDataService.addSequenceData(guid, request.sequenceData)
    }

    for {
      _ <- publicationFuture
      _ <- sequenceDataFuture
    } yield ()
  }

  private def linkPublication(sampleGuid: UUID, pubInfo: PublicationInfo): Future[Unit] = {
    for {
      maybeBiosample <- citizenBiosampleRepository.findByGuid(sampleGuid)
      biosample <- maybeBiosample match {
        case Some(b) => Future.successful(b)
        case None => Future.failed(new IllegalArgumentException(s"CitizenBiosample not found for GUID: $sampleGuid"))
      }

      maybePublication <- pubInfo.doi.map(doi =>
        publicationRepository.findByDoi(doi)
      ).getOrElse(Future.successful(None))

      publication <- maybePublication match {
        case Some(pub) => Future.successful(pub)
        case None => publicationRepository.savePublication(Publication(
          id = None,
          openAlexId = None,
          pubmedId = pubInfo.pubmedId,
          doi = pubInfo.doi,
          title = pubInfo.doi.map(d => s"Publication with DOI: $d").getOrElse("Unknown publication"),
          authors = None, abstractSummary = None, journal = None, publicationDate = None, url = None,
          citationNormalizedPercentile = None, citedByCount = None, openAccessStatus = None, openAccessUrl = None,
          primaryTopic = None, publicationType = None, publisher = None
        ))
      }

      _ <- publicationCitizenBiosampleRepository.create(PublicationCitizenBiosample(
        publicationId = publication.id.get,
        citizenBiosampleId = biosample.id.get
      ))

      _ <- pubInfo.originalHaplogroups.map { haplogroupInfo =>
        citizenBiosampleOriginalHaplogroupRepository.create(CitizenBiosampleOriginalHaplogroup(
          id = None,
          citizenBiosampleId = biosample.id.get,
          publicationId = publication.id.get,
          originalYHaplogroup = haplogroupInfo.yHaplogroup,
          originalMtHaplogroup = haplogroupInfo.mtHaplogroup,
          notes = haplogroupInfo.notes
        ))
      }.getOrElse(Future.successful(()))

    } yield ()
  }
}
