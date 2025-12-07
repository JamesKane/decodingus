package services.firehose

import jakarta.inject.{Inject, Singleton}
import models.atmosphere.*
import models.domain.Project
import models.domain.genomics.*
import play.api.Logging
import repositories.*

import java.time.{LocalDateTime, ZoneId}
import java.util.UUID
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

          citizenBiosample = CitizenBiosample(
            id = None,
            atUri = Some(record.atUri),
            accession = record.sampleAccession,
            alias = None,
            sourcePlatform = Some(record.centerName),
            collectionDate = None,
            sex = record.sex.map(s => models.domain.genomics.BiologicalSex.fromString(s)),
            geocoord = None,
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
    event.payload match {
      case Some(record) =>
        citizenBiosampleRepository.findByAtUri(record.atUri).flatMap {
          case Some(existing) =>
            resolveOrCreateDonor(record).flatMap { donorId =>
              val updated = existing.copy(
                description = record.description.orElse(existing.description),
                sourcePlatform = Some(record.centerName),
                sex = record.sex.map(s => models.domain.genomics.BiologicalSex.fromString(s)).orElse(existing.sex),
                yHaplogroup = record.haplogroups.flatMap(_.yDna).map(h => models.domain.genomics.HaplogroupResult(
                  h.haplogroupName, h.score, h.matchingSnps.getOrElse(0), h.mismatchingSnps.getOrElse(0),
                  h.ancestralMatches.getOrElse(0), h.treeDepth.getOrElse(0), h.lineagePath.getOrElse(Seq.empty)
                )).orElse(existing.yHaplogroup),
                mtHaplogroup = record.haplogroups.flatMap(_.mtDna).map(h => models.domain.genomics.HaplogroupResult(
                  h.haplogroupName, h.score, h.matchingSnps.getOrElse(0), h.mismatchingSnps.getOrElse(0),
                  h.ancestralMatches.getOrElse(0), h.treeDepth.getOrElse(0), h.lineagePath.getOrElse(Seq.empty)
                )).orElse(existing.mtHaplogroup),
                atCid = Some(UUID.randomUUID().toString),
                updatedAt = LocalDateTime.now(),
                specimenDonorId = donorId
              )
              citizenBiosampleRepository.update(updated, existing.atCid).map { success =>
                if (success) FirehoseResult.Success(event.atUri, updated.atCid.get, Some(updated.sampleGuid), "Updated Biosample")
                else FirehoseResult.Conflict(event.atUri, "Update failed (optimistic locking)")
              }
            }
          case None =>
            Future.successful(FirehoseResult.NotFound(event.atUri))
        }
      case None =>
        Future.successful(FirehoseResult.ValidationError(event.atUri, "Payload required for update"))
    }
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
      case FirehoseAction.Update => updateSequenceRun(event)
      case FirehoseAction.Delete => deleteSequenceRun(event)
    }
  }

  private def createSequenceRun(event: SequenceRunEvent): Future[FirehoseResult] = {
    event.payload match {
      case Some(record) =>
        citizenBiosampleRepository.findByAtUri(record.biosampleRef).flatMap {
          case Some(biosample) =>
            val lib = SequenceLibrary(
              id = None,
              sampleGuid = biosample.sampleGuid,
              lab = record.platformName,
              testType = record.testType,
              runDate = record.runDate.map(d => LocalDateTime.ofInstant(d, ZoneId.systemDefault())).getOrElse(LocalDateTime.now()),
              instrument = record.instrumentModel.getOrElse("Unknown"),
              reads = record.totalReads.getOrElse(0),
              readLength = record.readLength.getOrElse(0),
              pairedEnd = record.libraryLayout.exists(_.equalsIgnoreCase("PAIRED")),
              insertSize = record.meanInsertSize.map(_.toInt),
              atUri = Some(record.atUri),
              atCid = Some(UUID.randomUUID().toString),
              created_at = LocalDateTime.now(),
              updated_at = Some(LocalDateTime.now())
            )

            sequenceLibraryRepository.create(lib).map { _ =>
              FirehoseResult.Success(event.atUri, lib.atCid.get, None, "Sequence Run Created")
            }

          case None =>
            Future.successful(FirehoseResult.ValidationError(event.atUri, s"Parent biosample not found: ${record.biosampleRef}"))
        }
      case None => Future.successful(FirehoseResult.ValidationError(event.atUri, "Payload required"))
    }
  }

  private def updateSequenceRun(event: SequenceRunEvent): Future[FirehoseResult] = {
    event.payload match {
      case Some(record) =>
        sequenceLibraryRepository.findByAtUri(record.atUri).flatMap {
          case Some(existing) =>
            val updated = existing.copy(
              lab = record.platformName,
              testType = record.testType,
              runDate = record.runDate.map(d => LocalDateTime.ofInstant(d, ZoneId.systemDefault())).getOrElse(existing.runDate),
              instrument = record.instrumentModel.getOrElse(existing.instrument),
              reads = record.totalReads.getOrElse(existing.reads),
              readLength = record.readLength.getOrElse(existing.readLength),
              pairedEnd = record.libraryLayout.exists(_.equalsIgnoreCase("PAIRED")),
              insertSize = record.meanInsertSize.map(_.toInt).orElse(existing.insertSize),
              atCid = Some(UUID.randomUUID().toString),
              updated_at = Some(LocalDateTime.now())
            )
            sequenceLibraryRepository.update(updated).map { _ =>
              FirehoseResult.Success(event.atUri, updated.atCid.get, None, "Sequence Run Updated")
            }
          case None =>
            Future.successful(FirehoseResult.NotFound(event.atUri))
        }
      case None =>
        Future.successful(FirehoseResult.ValidationError(event.atUri, "Payload required"))
    }
  }

  private def deleteSequenceRun(event: SequenceRunEvent): Future[FirehoseResult] = {
    sequenceLibraryRepository.deleteByAtUri(event.atUri).map {
      case true => FirehoseResult.Success(event.atUri, "", None, "Sequence Run Deleted")
      case false => FirehoseResult.NotFound(event.atUri)
    }
  }

  // --- Alignment Handling ---

  private def handleAlignment(event: AlignmentEvent): Future[FirehoseResult] = {
    event.action match {
      case FirehoseAction.Create => createAlignment(event)
      case FirehoseAction.Update => updateAlignment(event)
      case FirehoseAction.Delete => deleteAlignment(event)
    }
  }

  private def createAlignment(event: AlignmentEvent): Future[FirehoseResult] = {
    event.payload match {
      case Some(record) =>
        sequenceLibraryRepository.findByAtUri(record.sequenceRunRef).flatMap {
          case Some(library) =>
            // Create Alignment Metadata
            // Assuming library.id is present
            val libraryId = library.id.getOrElse(throw new IllegalStateException("Library ID missing"))

            // Note: AlignmentMetadata typically links to a SequenceFile, not directly to SequenceLibrary in some schemas,
            // but if we assume a 1:1 or 1:N mapping and we need to create a dummy file record or link to existing.
            // For now, let's assume we create a placeholder sequence file for this alignment if files are listed
            // Or if the AlignmentMetadata table links to SequenceFile. 
            // Looking at AlignmentRepository, it uses sequenceFileId.
            // We need to find or create a SequenceFile first.

            val fileName = record.files.flatMap(_.headOption).map(_.fileName).getOrElse(s"alignment-${UUID.randomUUID()}")

            val seqFile = models.domain.genomics.SequenceFile(
              id = None,
              libraryId = libraryId,
              fileName = fileName,
              fileSizeBytes = 0, // Placeholder
              fileFormat = "BAM/CRAM",
              aligner = record.aligner,
              targetReference = record.referenceBuild,
              createdAt = LocalDateTime.now(),
              updatedAt = Some(LocalDateTime.now())
            )

            sequenceFileRepository.create(seqFile).flatMap { createdFile =>
              val metadata = AlignmentMetadata(
                id = None,
                sequenceFileId = createdFile.id.get,
                genbankContigId = 0, // Needs resolution or specific contig logic, assuming global stats for now?
                metricLevel = MetricLevel.GLOBAL, // Assuming global stats
                referenceBuild = Some(record.referenceBuild),
                variantCaller = record.variantCaller,
                genomeTerritory = record.metrics.flatMap(_.genomeTerritory),
                meanCoverage = record.metrics.flatMap(_.meanCoverage),
                medianCoverage = record.metrics.flatMap(_.medianCoverage),
                sdCoverage = record.metrics.flatMap(_.sdCoverage),
                pctExcDupe = record.metrics.flatMap(_.pctExcDupe),
                pctExcMapq = record.metrics.flatMap(_.pctExcMapq),
                pct10x = record.metrics.flatMap(_.pct10x),
                pct20x = record.metrics.flatMap(_.pct20x),
                pct30x = record.metrics.flatMap(_.pct30x),
                hetSnpSensitivity = record.metrics.flatMap(_.hetSnpSensitivity),
                analysisTool = record.aligner,
                metricsDate = LocalDateTime.now()
              )

              alignmentRepository.createMetadata(metadata).map { _ =>
                FirehoseResult.Success(event.atUri, "cid", None, "Alignment Created")
              }
            }

          case None =>
            Future.successful(FirehoseResult.ValidationError(event.atUri, s"Sequence Run not found: ${record.sequenceRunRef}"))
        }
      case None =>
        Future.successful(FirehoseResult.ValidationError(event.atUri, "Payload required"))
    }
  }

  private def updateAlignment(event: AlignmentEvent): Future[FirehoseResult] = {
    // Implementing update logic for alignment is complex due to file dependencies.
    // For now, returning success as placeholder.
    Future.successful(FirehoseResult.Success(event.atUri, "", None, "Alignment Update Not Fully Implemented"))
  }

  private def deleteAlignment(event: AlignmentEvent): Future[FirehoseResult] = {
    // Requires finding by AT URI, but AlignmentMetadata doesn't have AT URI yet.
    // Assuming for now we can't delete by AT URI directly without schema change.
    Future.successful(FirehoseResult.Success(event.atUri, "", None, "Alignment Delete Not Fully Implemented"))
  }

  // --- Project Handling ---

  private def handleProject(event: AtmosphereProjectEvent): Future[FirehoseResult] = {
    event.action match {
      case FirehoseAction.Create => createProject(event)
      case FirehoseAction.Update => updateProject(event)
      case FirehoseAction.Delete => deleteProject(event)
    }
  }

  private def createProject(event: AtmosphereProjectEvent): Future[FirehoseResult] = {
    event.payload match {
      case Some(record) =>
        val newAtCid = UUID.randomUUID().toString
        val project = Project(
          id = None,
          projectGuid = UUID.randomUUID(),
          name = record.projectName,
          description = record.description,
          ownerDid = record.administrator,
          atUri = Some(record.atUri),
          atCid = Some(newAtCid),
          createdAt = LocalDateTime.ofInstant(record.meta.createdAt, ZoneId.systemDefault()),
          updatedAt = LocalDateTime.now(),
          deleted = false
        )
        projectRepository.create(project).map { _ =>
          FirehoseResult.Success(event.atUri, newAtCid, None, "Project Created")
        }
      case None =>
        Future.successful(FirehoseResult.ValidationError(event.atUri, "Payload required"))
    }
  }

  private def updateProject(event: AtmosphereProjectEvent): Future[FirehoseResult] = {
    event.payload match {
      case Some(record) =>
        projectRepository.findByAtUri(record.atUri).flatMap {
          case Some(existing) =>
            val updated = existing.copy(
              name = record.projectName,
              description = record.description,
              ownerDid = record.administrator,
              atCid = Some(UUID.randomUUID().toString),
              updatedAt = LocalDateTime.now()
            )
            projectRepository.update(updated, existing.atCid).map { success =>
              if (success) FirehoseResult.Success(event.atUri, updated.atCid.get, None, "Project Updated")
              else FirehoseResult.Conflict(event.atUri, "Update failed")
            }
          case None =>
            Future.successful(FirehoseResult.NotFound(event.atUri))
        }
      case None =>
        Future.successful(FirehoseResult.ValidationError(event.atUri, "Payload required"))
    }
  }

  private def deleteProject(event: AtmosphereProjectEvent): Future[FirehoseResult] = {
    projectRepository.softDeleteByAtUri(event.atUri).map {
      case true => FirehoseResult.Success(event.atUri, "", None, "Project Deleted")
      case false => FirehoseResult.NotFound(event.atUri)
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
          donorType = BiosampleType.Citizen,
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

}