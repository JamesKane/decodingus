package services.firehose

import jakarta.inject.{Inject, Singleton}
import models.atmosphere._
import models.domain.genomics.{CitizenBiosample, SequenceLibrary, BiosampleType, SpecimenDonor}
import repositories._
import play.api.Logging
import java.util.UUID
import java.time.{LocalDateTime, ZoneId}
import scala.concurrent.{ExecutionContext, Future}

/**
 * Handles Atmosphere Lexicon events (Phase 3).
 * Processes granular records: Biosample, SequenceRun, Alignment, etc.
 */
@Singleton
class AtmosphereEventHandler @Inject()(
  citizenBiosampleRepository: CitizenBiosampleRepository,
  sequenceLibraryRepository: SequenceLibraryRepository,
  sequenceFileRepository: SequenceFileRepository,
  alignmentRepository: AlignmentRepository,
  specimenDonorRepository: SpecimenDonorRepository,
  projectRepository: ProjectRepository
)(implicit ec: ExecutionContext) extends Logging {

  def handle(event: FirehoseEvent): Future[FirehoseResult] = {
    event match {
      case e: BiosampleEvent => handleBiosample(e)
      case e: SequenceRunEvent => handleSequenceRun(e)
      case e: AlignmentEvent => handleAlignment(e)
      case e: AtmosphereProjectEvent => handleProject(e)
      // Add other handlers as needed
      case _ =>
        logger.warn(s"Unhandled event type: ${event.getClass.getSimpleName} for ${event.atUri}")
        Future.successful(FirehoseResult.Success(event.atUri, "", None, "Ignored (Not Implemented)"))
    }
  }

  // --- Biosample Handling ---

  private def handleBiosample(event: BiosampleEvent): Future[FirehoseResult] = {
    event.action match {
      case FirehoseAction.Create => createBiosample(event)
      case FirehoseAction.Update => updateBiosample(event)
      case FirehoseAction.Delete => deleteBiosample(event)
    }
  }

  private def createBiosample(event: BiosampleEvent): Future[FirehoseResult] = {
    event.payload match {
      case Some(record) =>
        for {
          donorId <- resolveOrCreateDonor(record)
          sampleGuid = UUID.randomUUID()
          newAtCid = UUID.randomUUID().toString // In real app, use record's CID if available or generated

          // Convert Atmosphere Haplogroups to internal model if needed
          // For now, mapping basics

          citizenBiosample = CitizenBiosample(
            id = None,
            atUri = Some(record.atUri),
            accession = record.sampleAccession,
            alias = None, // Not in BiosampleRecord directly, maybe Description?
            sourcePlatform = Some(record.centerName), // Mapping centerName to sourcePlatform or similar
            collectionDate = None,
            sex = record.sex.map(s => models.domain.genomics.BiologicalSex.fromString(s)),
            geocoord = None, // Not in BiosampleRecord
            description = record.description,
            yHaplogroup = record.haplogroups.flatMap(_.yDna).map(h => models.domain.genomics.HaplogroupResult(
              h.haplogroupName,
              h.score,
              h.matchingSnps.getOrElse(0),
              h.mismatchingSnps.getOrElse(0),
              h.ancestralMatches.getOrElse(0),
              h.treeDepth.getOrElse(0),
              h.lineagePath.getOrElse(Seq.empty)
            )),
            mtHaplogroup = record.haplogroups.flatMap(_.mtDna).map(h => models.domain.genomics.HaplogroupResult(
              h.haplogroupName,
              h.score,
              h.matchingSnps.getOrElse(0),
              h.mismatchingSnps.getOrElse(0),
              h.ancestralMatches.getOrElse(0),
              h.treeDepth.getOrElse(0),
              h.lineagePath.getOrElse(Seq.empty)
            )),
            sampleGuid = sampleGuid,
            deleted = false,
            atCid = Some(newAtCid),
            createdAt = LocalDateTime.ofInstant(record.meta.createdAt, ZoneId.systemDefault()),
            updatedAt = LocalDateTime.ofInstant(record.meta.updatedAt.getOrElse(record.meta.createdAt), ZoneId.systemDefault()),
            specimenDonorId = donorId
          )

          created <- citizenBiosampleRepository.create(citizenBiosample)
        } yield FirehoseResult.Success(event.atUri, newAtCid, Some(created.sampleGuid), "Created Biosample")

      case None =>
        Future.successful(FirehoseResult.ValidationError(event.atUri, "Payload required for create"))
    }
  }

  private def updateBiosample(event: BiosampleEvent): Future[FirehoseResult] = {
    // Logic similar to create but fetching existing by atUri and updating
    // Placeholder implementation
    Future.successful(FirehoseResult.Success(event.atUri, "new-cid", None, "Update Not Implemented Fully"))
  }

  private def deleteBiosample(event: BiosampleEvent): Future[FirehoseResult] = {
    citizenBiosampleRepository.softDeleteByAtUri(event.atUri).map {
      case true => FirehoseResult.Success(event.atUri, "", None, "Deleted")
      case false => FirehoseResult.NotFound(event.atUri)
    }
  }

  // --- Sequence Run Handling ---

  private def handleSequenceRun(event: SequenceRunEvent): Future[FirehoseResult] = {
    event.action match {
      case FirehoseAction.Create => createSequenceRun(event)
      case _ => Future.successful(FirehoseResult.Success(event.atUri, "", None, "Action Not Implemented"))
    }
  }

  private def createSequenceRun(event: SequenceRunEvent): Future[FirehoseResult] = {
    event.payload match {
      case Some(record) =>
        // 1. Find parent biosample
        citizenBiosampleRepository.findByAtUri(record.biosampleRef).flatMap {
          case Some(biosample) =>
            // 2. Create SequenceLibrary (SequenceRun)
             val lib = SequenceLibrary(
               id = None,
               sampleGuid = biosample.sampleGuid,
               lab = record.platformName, // Temporary mapping, ideally centerName from biosample or new field
               testType = record.testType,
               runDate = record.runDate.map(d => LocalDateTime.ofInstant(d, ZoneId.systemDefault())).getOrElse(LocalDateTime.now()),
               instrument = record.instrumentModel.getOrElse("Unknown"),
               reads = record.totalReads.getOrElse(0),
               readLength = record.readLength.getOrElse(0),
               pairedEnd = record.libraryLayout.exists(_.equalsIgnoreCase("PAIRED")),
               insertSize = record.meanInsertSize.map(_.toInt),
               created_at = LocalDateTime.now(),
               updated_at = Some(LocalDateTime.now())
             )
             
             sequenceLibraryRepository.create(lib).map { _ =>
               FirehoseResult.Success(event.atUri, "cid-placeholder", None, "Sequence Run Created")
             }

          case None =>
            Future.successful(FirehoseResult.ValidationError(event.atUri, s"Parent biosample not found: ${record.biosampleRef}"))
        }
      case None => Future.successful(FirehoseResult.ValidationError(event.atUri, "Payload required"))
    }
  }

  // --- Helpers ---

  private def resolveOrCreateDonor(record: BiosampleRecord): Future[Option[Int]] = {
    val citizenDid = record.citizenDid
    val identifier = record.donorIdentifier
    
    specimenDonorRepository.findByDidAndIdentifier(citizenDid, identifier).flatMap {
      case Some(donor) => Future.successful(donor.id)
      case None =>
        val newDonor = SpecimenDonor(
          donorIdentifier = identifier,
          originBiobank = record.centerName,
          donorType = BiosampleType.Citizen, // Default
          sex = record.sex.map(s => models.domain.genomics.BiologicalSex.fromString(s)),
          geocoord = None,
          pgpParticipantId = None,
          atUri = Some(citizenDid),
          dateRangeStart = None,
          dateRangeEnd = None
        )
        specimenDonorRepository.create(newDonor).map(_.id)
    }
  }
  
  // --- Stubs for other handlers ---
  private def handleAlignment(event: AlignmentEvent): Future[FirehoseResult] = {
    Future.successful(FirehoseResult.Success(event.atUri, "", None, "Alignment ignored"))
  }
  
  private def handleProject(event: AtmosphereProjectEvent): Future[FirehoseResult] = {
    Future.successful(FirehoseResult.Success(event.atUri, "", None, "Project ignored"))
  }

}
