package services

import jakarta.inject.{Inject, Singleton}
import models.api.{ExternalBiosampleRequest, PublicationInfo}
import models.domain.genomics.{BiosampleType, CitizenBiosample, SpecimenDonor}
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
                                          citizenBiosampleOriginalHaplogroupRepository: CitizenBiosampleOriginalHaplogroupRepository,
                                          specimenDonorRepository: SpecimenDonorRepository
                                        )(implicit ec: ExecutionContext) extends CoordinateValidation {

  /**
   * Extracts the DID from an AT URI.
   * AT URI format: at://did:plc:abc123/collection/rkey
   */
  private def extractDidFromAtUri(atUri: String): Option[String] = {
    if (atUri.startsWith("at://")) {
      val withoutPrefix = atUri.stripPrefix("at://")
      val didEnd = withoutPrefix.indexOf('/')
      if (didEnd > 0) Some(withoutPrefix.substring(0, didEnd))
      else Some(withoutPrefix)
    } else None
  }

  /**
   * Resolves or creates a SpecimenDonor for a Citizen biosample.
   * Uses citizenDid (extracted from atUri) + donorIdentifier to find existing donor,
   * or creates a new one if not found.
   */
  private def resolveOrCreateDonor(
                                    request: ExternalBiosampleRequest,
                                    geocoord: Option[com.vividsolutions.jts.geom.Point]
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

  def createBiosample(request: ExternalBiosampleRequest): Future[UUID] = {
    // 1. Validate coordinates
    validateCoordinates(request.latitude, request.longitude).flatMap { geocoord =>
      // 2. Check for existing biosample by accession
      citizenBiosampleRepository.findByAccession(request.sampleAccession).flatMap {
        case Some(_) =>
          Future.failed(new IllegalArgumentException(s"Biosample with accession ${request.sampleAccession} already exists."))

        case None =>
           // 3. Resolve or create SpecimenDonor
           resolveOrCreateDonor(request, geocoord).flatMap { donorId =>
             // 4. Create new CitizenBiosample
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
               yHaplogroup = request.haplogroups.flatMap(_.yDna),
               mtHaplogroup = request.haplogroups.flatMap(_.mtDna),
               sampleGuid = sampleGuid,
               deleted = false,
               atCid = newAtCid,
               createdAt = LocalDateTime.now(),
               updatedAt = LocalDateTime.now(),
               specimenDonorId = donorId
             )

             for {
               created <- citizenBiosampleRepository.create(citizenBiosample)
               _ <- handleDataAssociation(created.sampleGuid, request, isUpdate = false)
             } yield created.sampleGuid
           }
      }
    }
  }

  def updateBiosample(atUri: String, request: ExternalBiosampleRequest): Future[UUID] = {
    validateCoordinates(request.latitude, request.longitude).flatMap { geocoord =>
      citizenBiosampleRepository.findByAtUri(atUri).flatMap {
        case Some(existing) =>
           // Optimistic Locking Check
           if (request.atCid.isDefined && request.atCid != existing.atCid) {
             Future.failed(new IllegalStateException(s"Optimistic locking failure: atCid mismatch. Expected ${existing.atCid}, got ${request.atCid}"))
           } else {
             // Resolve donor (use existing if not changing, or resolve/create if provided)
             val donorFuture = if (request.donorIdentifier.isDefined) {
               resolveOrCreateDonor(request, geocoord)
             } else {
               Future.successful(existing.specimenDonorId)
             }

             donorFuture.flatMap { donorId =>
               val newAtCid = Some(UUID.randomUUID().toString)
               val toUpdate = existing.copy(
                 description = Some(request.description),
                 alias = request.alias,
                 sourcePlatform = Some(request.sourceSystem),
                 sex = request.sex,
                 geocoord = geocoord,
                 atUri = request.atUri,
                 accession = Some(request.sampleAccession),
                 yHaplogroup = request.haplogroups.flatMap(_.yDna).orElse(existing.yHaplogroup),
                 mtHaplogroup = request.haplogroups.flatMap(_.mtDna).orElse(existing.mtHaplogroup),
                 atCid = newAtCid,
                 updatedAt = LocalDateTime.now(),
                 specimenDonorId = donorId
               )

               citizenBiosampleRepository.update(toUpdate, request.atCid).flatMap { success =>
                 if (success) {
                   handleDataAssociation(existing.sampleGuid, request, isUpdate = true).map(_ => existing.sampleGuid)
                 } else {
                   Future.failed(new RuntimeException("Update failed (optimistic lock or record missing)"))
                 }
               }
             }
           }
        case None =>
          Future.failed(new NoSuchElementException(s"Biosample not found for atUri: $atUri"))
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

  def deleteBiosample(atUri: String): Future[Boolean] = {
    citizenBiosampleRepository.softDeleteByAtUri(atUri)
  }
}