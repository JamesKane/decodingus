package services

import jakarta.inject.{Inject, Singleton}
import models.api.{ExternalBiosampleRequest, PublicationInfo}
import models.domain.genomics.{BiosampleType, CitizenBiosample}
import models.domain.publications.{CitizenBiosampleOriginalHaplogroup, Publication, PublicationCitizenBiosample}
import repositories._

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CitizenBiosampleService @Inject()(
                                          citizenBiosampleRepository: CitizenBiosampleRepository,
                                          biosampleDataService: BiosampleDataService,
                                          publicationRepository: PublicationRepository,
                                          publicationCitizenBiosampleRepository: PublicationCitizenBiosampleRepository,
                                          citizenBiosampleOriginalHaplogroupRepository: CitizenBiosampleOriginalHaplogroupRepository
                                        )(implicit ec: ExecutionContext) extends CoordinateValidation {

  def createBiosample(request: ExternalBiosampleRequest): Future[UUID] = {
    // 1. Validate coordinates
    validateCoordinates(request.latitude, request.longitude).flatMap { geocoord =>
      // 2. Check for existing biosample by accession
      citizenBiosampleRepository.findByAccession(request.sampleAccession).flatMap {
        case Some(_) => 
          Future.failed(new IllegalArgumentException(s"Biosample with accession ${request.sampleAccession} already exists."))
        
        case None =>
           // Create new
           val sampleGuid = UUID.randomUUID()
           val newAtCid = Some(UUID.randomUUID().toString)
           
           val citizenBiosample = CitizenBiosample(
             id = None,
             atUri = request.atUri,
             accession = Some(request.sampleAccession),
             alias = request.alias,
             sourcePlatform = Some(request.sourceSystem),
             collectionDate = None,
             sex = request.sex,
             geocoord = geocoord,
             description = Some(request.description),
             sampleGuid = sampleGuid,
             deleted = false,
             atCid = newAtCid,
             createdAt = LocalDateTime.now(),
             updatedAt = LocalDateTime.now()
           )
           
           for {
             created <- citizenBiosampleRepository.create(citizenBiosample)
             _ <- handleDataAssociation(created.sampleGuid, request, isUpdate = false)
           } yield created.sampleGuid
      }
    }
  }

  def updateBiosample(sampleGuid: UUID, request: ExternalBiosampleRequest): Future[UUID] = {
    validateCoordinates(request.latitude, request.longitude).flatMap { geocoord =>
      citizenBiosampleRepository.findByGuid(sampleGuid).flatMap {
        case Some(existing) =>
           // Optimistic Locking Check
           if (request.atCid.isDefined && request.atCid != existing.atCid) {
             Future.failed(new IllegalStateException(s"Optimistic locking failure: atCid mismatch. Expected ${existing.atCid}, got ${request.atCid}"))
           } else {
             val newAtCid = Some(UUID.randomUUID().toString)
             val toUpdate = existing.copy(
               description = Some(request.description),
               alias = request.alias,
               sourcePlatform = Some(request.sourceSystem),
               sex = request.sex,
               geocoord = geocoord,
               atUri = request.atUri,
               accession = Some(request.sampleAccession),
               atCid = newAtCid,
               updatedAt = LocalDateTime.now()
             )
             
             citizenBiosampleRepository.update(toUpdate, request.atCid).flatMap { success =>
               if (success) {
                 handleDataAssociation(existing.sampleGuid, request, isUpdate = true).map(_ => existing.sampleGuid)
               } else {
                 Future.failed(new RuntimeException("Update failed (optimistic lock or record missing)"))
               }
             }
           }
        case None =>
          Future.failed(new NoSuchElementException(s"Biosample not found for GUID: $sampleGuid"))
      }
    }
  }
  
  private def handleDataAssociation(guid: UUID, request: ExternalBiosampleRequest, isUpdate: Boolean): Future[Unit] = {
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
      
      // Link publication
      _ <- publicationCitizenBiosampleRepository.create(PublicationCitizenBiosample(
        publicationId = publication.id.get,
        citizenBiosampleId = biosample.id.get
      ))
      
      // Link Haplogroups
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

  def deleteBiosample(sampleGuid: UUID): Future[Boolean] = {
    citizenBiosampleRepository.softDelete(sampleGuid)
  }
}
