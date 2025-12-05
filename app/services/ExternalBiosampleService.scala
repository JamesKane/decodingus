package services

import com.vividsolutions.jts.geom.Point
import jakarta.inject.{Inject, Singleton}
import models.api.ExternalBiosampleRequest
import models.domain.genomics.{Biosample, BiosampleType, SpecimenDonor}
import repositories.{BiosampleRepository, SpecimenDonorRepository}
import utils.GeometryUtils

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/**
 * ExternalBiosampleService provides functionality for creating a new biosample
 * and associating it with its metadata and sequence data within the system.
 *
 * @constructor Creates an instance of ExternalBiosampleService with the required
 *              dependencies injected.
 * @param biosampleRepository  an instance of BiosampleRepository for managing
 *                             biosample data in the data store.
 * @param biosampleDataService an instance of BiosampleDataService responsible
 *                             for handling biosample-related data operations such
 *                             as linking publications and adding sequence data.
 * @param ec                   an implicit ExecutionContext for handling asynchronous operations.
 */
@Singleton
class ExternalBiosampleService @Inject()(
                                          biosampleRepository: BiosampleRepository,
                                          biosampleDataService: BiosampleDataService,
                                          specimenDonorRepository: SpecimenDonorRepository
                                        )(implicit ec: ExecutionContext) extends CoordinateValidation {

  def createBiosampleWithData(request: ExternalBiosampleRequest): Future[UUID] = {
    val sampleGuid = UUID.randomUUID()

    def createSpecimenDonor(geocoord: Option[Point]) = {
      val donor = SpecimenDonor(
        donorIdentifier = s"DONOR_${UUID.randomUUID().toString}",
        originBiobank = request.centerName,
        donorType = BiosampleType.Standard,
        sex = request.sex,
        geocoord = geocoord,
        pgpParticipantId = None,
        citizenBiosampleDid = None,
        dateRangeStart = None,
        dateRangeEnd = None
      )
      specimenDonorRepository.create(donor)
    }

    def createBiosample(donorId: Option[Int]) = {
      val biosample = Biosample(
        id = None,
        sampleGuid = sampleGuid,
        sampleAccession = request.sampleAccession,
        description = request.description,
        alias = request.alias,
        centerName = request.centerName,
        specimenDonorId = donorId,
        locked = false,
        sourcePlatform = Some(request.sourceSystem)
      )

      biosampleRepository.create(biosample)
    }

    def updateBiosample(existingBiosample: Biosample, donorId: Option[Int]) = {
      val updatedBiosample = existingBiosample.copy(
        description = request.description,
        alias = request.alias,
        centerName = request.centerName,
        specimenDonorId = donorId,
        sourcePlatform = Some(request.sourceSystem)
      )
      biosampleRepository.update(updatedBiosample).map(_ => existingBiosample.sampleGuid)
    }

    def handleDataAssociation(guid: UUID, isUpdate: Boolean) = {
      val publicationFuture = request.publication
        .map(pub => biosampleDataService.linkPublication(guid, pub)
          .recoverWith { case e =>
            Future.failed(PublicationLinkageException(e.getMessage))
          })
        .getOrElse(Future.successful(()))

      val sequenceDataFuture = if (isUpdate) {
        biosampleDataService.replaceSequenceData(guid, request.sequenceData)
      } else {
        biosampleDataService.addSequenceData(guid, request.sequenceData)
      }

      for {
        _ <- publicationFuture
        _ <- sequenceDataFuture.recoverWith { case e =>
          Future.failed(SequenceDataValidationException(e.getMessage))
        }
      } yield guid
    }

    def shouldCreateDonor: Boolean = {
      request.sex.isDefined || request.latitude.isDefined || request.longitude.isDefined
    }

    def handleCitizenDonor(): Future[Option[Int]] = {
      (request.citizenDid, request.donorIdentifier) match {
        case (Some(did), Some(identifier)) =>
          specimenDonorRepository.findByDidAndIdentifier(did, identifier).flatMap {
            case Some(existingDonor) => Future.successful(existingDonor.id)
            case None =>
              val newDonor = SpecimenDonor(
                donorIdentifier = identifier,
                originBiobank = request.centerName,
                donorType = request.donorType.getOrElse(BiosampleType.Citizen),
                sex = request.sex,
                geocoord = None, // Coordinates handled separately if needed, or could be passed here
                pgpParticipantId = None,
                citizenBiosampleDid = Some(did),
                dateRangeStart = None,
                dateRangeEnd = None
              )
              specimenDonorRepository.create(newDonor).map(_.id)
          }
        case _ => Future.successful(None)
      }
    }

    (for {
      geocoord <- validateCoordinates(request.latitude, request.longitude)
      citizenDonorId <- handleCitizenDonor()
      donorId <- if (citizenDonorId.isDefined) {
        Future.successful(citizenDonorId)
      } else if (shouldCreateDonor) {
        createSpecimenDonor(geocoord).map(donor => Some(donor.id.get))
      } else {
        Future.successful(None)
      }
      
      // Check for existing accession
      existing <- biosampleRepository.findByAccession(request.sampleAccession)
      
      guid <- existing match {
        case Some((existingBiosample, _)) =>
          // Update existing
          for {
            guid <- updateBiosample(existingBiosample, donorId)
            _ <- handleDataAssociation(guid, isUpdate = true)
          } yield guid
          
        case None =>
          // Create new
          for {
            created <- createBiosample(donorId)
            guid <- handleDataAssociation(created.sampleGuid, isUpdate = false)
          } yield guid
      }

    } yield guid).recoverWith {
      case e: BiosampleServiceException => Future.failed(e)
      case e: Exception => Future.failed(new RuntimeException(
        s"Failed to process biosample: ${e.getMessage}", e))
    }
  }
}