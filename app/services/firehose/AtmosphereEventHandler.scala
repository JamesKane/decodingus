package services.firehose

import jakarta.inject.{Inject, Singleton}
import models.atmosphere.*
import models.domain.{GroupProject, GroupProjectMember, Project}
import models.domain.genomics.*
import play.api.Logging
import repositories.*
import services.TestTypeService

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
                                        projectRepository: ProjectRepository,
                                        testTypeService: TestTypeService,
                                        genotypeDataRepository: GenotypeDataRepository,
                                        populationBreakdownRepository: PopulationBreakdownRepository,
                                        haplogroupReconciliationRepository: HaplogroupReconciliationRepository,
                                        instrumentObservationRepository: InstrumentObservationRepository,
                                        groupProjectRepository: GroupProjectRepository,
                                        groupProjectMemberRepository: GroupProjectMemberRepository
                                      )(implicit ec: ExecutionContext) extends Logging {

  def handle(event: FirehoseEvent): Future[FirehoseResult] = {
    event match {
      case e: BiosampleEvent => handleBiosample(e)
      case e: SequenceRunEvent => handleSequenceRun(e)
      case e: AlignmentEvent => handleAlignment(e)
      case e: AtmosphereProjectEvent => handleProject(e)
      case e: GenotypeEvent => handleGenotype(e)
      case e: PopulationBreakdownEvent => handlePopulationBreakdown(e)
      case e: HaplogroupReconciliationEvent => handleHaplogroupReconciliation(e)
      case e: InstrumentObservationEvent => handleInstrumentObservation(e)
      case e: GroupProjectEvent => handleGroupProject(e)
      case e: ProjectMembershipEvent => handleProjectMembership(e)
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
            testTypeService.getByCode(record.testType).flatMap {
              case Some(testTypeRow) =>
                val testTypeId = testTypeRow.id.getOrElse(throw new IllegalStateException("TestTypeRow ID not found"))
                val lib = SequenceLibrary(
                  id = None,
                  sampleGuid = biosample.sampleGuid,
                  lab = record.platformName,
                  testTypeId = testTypeId, // <--- Changed to testTypeId
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
                Future.successful(FirehoseResult.ValidationError(event.atUri, s"Invalid test type code: ${record.testType}"))
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
            testTypeService.getByCode(record.testType).flatMap {
              case Some(testTypeRow) =>
                val testTypeId = testTypeRow.id.getOrElse(throw new IllegalStateException("TestTypeRow ID not found"))
                val updated = existing.copy(
                  lab = record.platformName,
                  testTypeId = testTypeId, // <--- Changed to testTypeId
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
                Future.successful(FirehoseResult.ValidationError(event.atUri, s"Invalid test type code: ${record.testType}"))
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
            val libraryId = library.id.getOrElse(throw new IllegalStateException("Library ID missing"))

            val fileName = record.files.flatMap(_.headOption).map(_.fileName).getOrElse(s"alignment-${UUID.randomUUID()}")

            val checksumsJsonb = record.files.flatMap(_.headOption).flatMap(_.checksum.map {
              cs =>
                models.domain.genomics.SequenceFileChecksumJsonb(
                  checksum = cs,
                  algorithm = record.files.flatMap(_.headOption.flatMap(_.checksumAlgorithm)).getOrElse("UNKNOWN"),
                  verifiedAt = Some(LocalDateTime.now()),
                  createdAt = LocalDateTime.now(),
                  updatedAt = LocalDateTime.now()
                )
            }).toList

            val httpLocationsJsonb = record.files.flatMap(_.headOption).flatMap(_.location.map {
              loc =>
                models.domain.genomics.SequenceFileHttpLocationJsonb(
                  url = loc,
                  urlHash = UUID.nameUUIDFromBytes(loc.getBytes).toString, // Generate hash from URL
                  createdAt = LocalDateTime.now(),
                  updatedAt = LocalDateTime.now()
                )
            }).toList

            val seqFile = models.domain.genomics.SequenceFile(
              id = None,
              libraryId = libraryId,
              fileName = fileName,
              fileSizeBytes = record.files.flatMap(_.headOption.flatMap(_.fileSizeBytes)).getOrElse(0L), // Use actual file size if available
              fileFormat = "BAM/CRAM", // Or derive from record.files if available
              checksums = checksumsJsonb,
              httpLocations = httpLocationsJsonb,
              atpLocation = None, // No direct mapping from FileInfo
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

  // --- Genotype Handling ---

  private def handleGenotype(event: GenotypeEvent): Future[FirehoseResult] = {
    event.action match {
      case FirehoseAction.Create => createGenotype(event)
      case FirehoseAction.Update => updateGenotype(event)
      case FirehoseAction.Delete => deleteGenotype(event)
    }
  }

  private def createGenotype(event: GenotypeEvent): Future[FirehoseResult] = {
    event.payload match {
      case Some(record) =>
        citizenBiosampleRepository.findByAtUri(record.biosampleRef).flatMap {
          case Some(biosample) =>
            testTypeService.getByCode(record.testTypeCode).flatMap {
              case Some(testTypeRow) =>
                val metrics = GenotypeMetrics(
                  totalMarkersCalled = record.totalMarkersCalled,
                  totalMarkersPossible = record.totalMarkersPossible,
                  callRate = record.callRate,
                  noCallRate = record.noCallRate,
                  yMarkersCalled = record.yMarkersCalled,
                  yMarkersTotal = record.yMarkersTotal,
                  mtMarkersCalled = record.mtMarkersCalled,
                  mtMarkersTotal = record.mtMarkersTotal,
                  autosomalMarkersCalled = record.autosomalMarkersCalled,
                  hetRate = record.hetRate,
                  testDate = record.testDate.map(i => LocalDateTime.ofInstant(i, ZoneId.systemDefault())),
                  processedAt = record.processedAt.map(i => LocalDateTime.ofInstant(i, ZoneId.systemDefault())),
                  derivedYHaplogroup = record.derivedHaplogroups.flatMap(_.yDna).map(h => models.domain.genomics.HaplogroupResult(
                    h.haplogroupName, h.score, h.matchingSnps.getOrElse(0), h.mismatchingSnps.getOrElse(0),
                    h.ancestralMatches.getOrElse(0), h.treeDepth.getOrElse(0), h.lineagePath.getOrElse(Seq.empty)
                  )),
                  derivedMtHaplogroup = record.derivedHaplogroups.flatMap(_.mtDna).map(h => models.domain.genomics.HaplogroupResult(
                    h.haplogroupName, h.score, h.matchingSnps.getOrElse(0), h.mismatchingSnps.getOrElse(0),
                    h.ancestralMatches.getOrElse(0), h.treeDepth.getOrElse(0), h.lineagePath.getOrElse(Seq.empty)
                  )),
                  files = record.files
                )

                val genotypeData = GenotypeData(
                  id = None,
                  atUri = Some(record.atUri),
                  atCid = Some(UUID.randomUUID().toString),
                  sampleGuid = biosample.sampleGuid,
                  testTypeId = testTypeRow.id,
                  provider = Some(record.provider),
                  chipVersion = record.chipVersion,
                  buildVersion = record.buildVersion,
                  sourceFileHash = record.sourceFileHash,
                  metrics = metrics,
                  populationBreakdownId = None, // Will be linked when PopulationBreakdown is created
                  deleted = false,
                  createdAt = LocalDateTime.ofInstant(record.meta.createdAt, ZoneId.systemDefault()),
                  updatedAt = LocalDateTime.now()
                )

                genotypeDataRepository.create(genotypeData).map { created =>
                  FirehoseResult.Success(event.atUri, created.atCid.getOrElse(""), Some(created.sampleGuid), "Genotype Created")
                }
              case None =>
                Future.successful(FirehoseResult.ValidationError(event.atUri, s"Invalid test type code: ${record.testTypeCode}"))
            }
          case None =>
            Future.successful(FirehoseResult.ValidationError(event.atUri, s"Biosample not found: ${record.biosampleRef}"))
        }
      case None =>
        Future.successful(FirehoseResult.ValidationError(event.atUri, "Payload required"))
    }
  }

  private def updateGenotype(event: GenotypeEvent): Future[FirehoseResult] = {
    event.payload match {
      case Some(record) =>
        genotypeDataRepository.findByAtUri(record.atUri).flatMap {
          case Some(existing) =>
            val updatedMetrics = existing.metrics.copy(
              totalMarkersCalled = record.totalMarkersCalled.orElse(existing.metrics.totalMarkersCalled),
              totalMarkersPossible = record.totalMarkersPossible.orElse(existing.metrics.totalMarkersPossible),
              callRate = record.callRate.orElse(existing.metrics.callRate),
              noCallRate = record.noCallRate.orElse(existing.metrics.noCallRate),
              testDate = record.testDate.map(i => LocalDateTime.ofInstant(i, ZoneId.systemDefault())).orElse(existing.metrics.testDate),
              processedAt = record.processedAt.map(i => LocalDateTime.ofInstant(i, ZoneId.systemDefault())).orElse(existing.metrics.processedAt),
              files = record.files.orElse(existing.metrics.files)
            )

            val updated = existing.copy(
              provider = Some(record.provider),
              chipVersion = record.chipVersion.orElse(existing.chipVersion),
              buildVersion = record.buildVersion.orElse(existing.buildVersion),
              sourceFileHash = record.sourceFileHash.orElse(existing.sourceFileHash),
              metrics = updatedMetrics,
              atCid = Some(UUID.randomUUID().toString),
              updatedAt = LocalDateTime.now()
            )

            genotypeDataRepository.update(updated).map { success =>
              if (success) FirehoseResult.Success(event.atUri, updated.atCid.getOrElse(""), Some(updated.sampleGuid), "Genotype Updated")
              else FirehoseResult.Conflict(event.atUri, "Update failed")
            }
          case None =>
            Future.successful(FirehoseResult.NotFound(event.atUri))
        }
      case None =>
        Future.successful(FirehoseResult.ValidationError(event.atUri, "Payload required"))
    }
  }

  private def deleteGenotype(event: GenotypeEvent): Future[FirehoseResult] = {
    genotypeDataRepository.findByAtUri(event.atUri).flatMap {
      case Some(existing) =>
        genotypeDataRepository.softDelete(existing.id.get).map {
          case true => FirehoseResult.Success(event.atUri, "", None, "Genotype Deleted")
          case false => FirehoseResult.NotFound(event.atUri)
        }
      case None =>
        Future.successful(FirehoseResult.NotFound(event.atUri))
    }
  }

  // --- Population Breakdown Handling ---

  private def handlePopulationBreakdown(event: PopulationBreakdownEvent): Future[FirehoseResult] = {
    event.action match {
      case FirehoseAction.Create => createPopulationBreakdown(event)
      case FirehoseAction.Update => updatePopulationBreakdown(event)
      case FirehoseAction.Delete => deletePopulationBreakdown(event)
    }
  }

  private def createPopulationBreakdown(event: PopulationBreakdownEvent): Future[FirehoseResult] = {
    event.payload match {
      case Some(record) =>
        citizenBiosampleRepository.findByAtUri(record.biosampleRef).flatMap {
          case Some(biosample) =>
            val pcaCoords = record.pcaCoordinates.map { coords =>
              PcaCoordinatesJsonb(
                coords.headOption.getOrElse(0.0),
                coords.lift(1).getOrElse(0.0),
                coords.lift(2).getOrElse(0.0)
              )
            }

            val breakdown = PopulationBreakdown(
              id = None,
              atUri = Some(record.atUri),
              atCid = Some(UUID.randomUUID().toString),
              sampleGuid = biosample.sampleGuid,
              analysisMethod = record.analysisMethod,
              panelType = record.panelType,
              referencePopulations = record.referencePopulations,
              referenceVersion = record.referenceVersion,
              snpsAnalyzed = record.snpsAnalyzed,
              snpsWithGenotype = record.snpsWithGenotype,
              snpsMissing = record.snpsMissing,
              confidenceLevel = record.confidenceLevel,
              pcaCoordinates = pcaCoords,
              analysisDate = record.analysisDate.map(i => LocalDateTime.ofInstant(i, ZoneId.systemDefault())),
              pipelineVersion = record.pipelineVersion,
              deleted = false,
              createdAt = LocalDateTime.ofInstant(record.meta.createdAt, ZoneId.systemDefault()),
              updatedAt = LocalDateTime.now()
            )

            populationBreakdownRepository.create(breakdown).flatMap { created =>
              // Create population components
              val componentsFuture = populationBreakdownRepository.upsertComponentsByBreakdownId(
                created.id.get,
                record.components.map { c =>
                  models.domain.genomics.PopulationComponent(
                    id = None,
                    populationBreakdownId = created.id.get,
                    populationCode = c.populationCode,
                    populationName = c.populationName,
                    superPopulation = c.superPopulation,
                    percentage = c.percentage,
                    confidenceLower = c.confidenceInterval.flatMap(_.get("lower")),
                    confidenceUpper = c.confidenceInterval.flatMap(_.get("upper")),
                    rank = c.rank
                  )
                }
              )

              // Create super population summaries if present
              val summariesFuture = record.superPopulationSummary match {
                case Some(summaries) =>
                  populationBreakdownRepository.upsertSummariesByBreakdownId(
                    created.id.get,
                    summaries.map { s =>
                      models.domain.genomics.SuperPopulationSummary(
                        id = None,
                        populationBreakdownId = created.id.get,
                        superPopulation = s.superPopulation,
                        percentage = s.percentage,
                        populations = Some(SuperPopulationListJsonb(s.populations))
                      )
                    }
                  )
                case None => Future.successful(Seq.empty)
              }

              for {
                _ <- componentsFuture
                _ <- summariesFuture
              } yield FirehoseResult.Success(event.atUri, created.atCid.getOrElse(""), Some(created.sampleGuid), "Population Breakdown Created")
            }
          case None =>
            Future.successful(FirehoseResult.ValidationError(event.atUri, s"Biosample not found: ${record.biosampleRef}"))
        }
      case None =>
        Future.successful(FirehoseResult.ValidationError(event.atUri, "Payload required"))
    }
  }

  private def updatePopulationBreakdown(event: PopulationBreakdownEvent): Future[FirehoseResult] = {
    event.payload match {
      case Some(record) =>
        populationBreakdownRepository.findByAtUri(record.atUri).flatMap {
          case Some(existing) =>
            val updated = existing.copy(
              analysisMethod = record.analysisMethod,
              panelType = record.panelType.orElse(existing.panelType),
              referencePopulations = record.referencePopulations.orElse(existing.referencePopulations),
              confidenceLevel = record.confidenceLevel.orElse(existing.confidenceLevel),
              atCid = Some(UUID.randomUUID().toString),
              updatedAt = LocalDateTime.now()
            )

            for {
              success <- populationBreakdownRepository.update(updated)
              _ <- populationBreakdownRepository.upsertComponentsByBreakdownId(
                existing.id.get,
                record.components.map { c =>
                  models.domain.genomics.PopulationComponent(
                    id = None,
                    populationBreakdownId = existing.id.get,
                    populationCode = c.populationCode,
                    populationName = c.populationName,
                    superPopulation = c.superPopulation,
                    percentage = c.percentage,
                    confidenceLower = c.confidenceInterval.flatMap(_.get("lower")),
                    confidenceUpper = c.confidenceInterval.flatMap(_.get("upper")),
                    rank = c.rank
                  )
                }
              )
            } yield {
              if (success) FirehoseResult.Success(event.atUri, updated.atCid.getOrElse(""), Some(updated.sampleGuid), "Population Breakdown Updated")
              else FirehoseResult.Conflict(event.atUri, "Update failed")
            }
          case None =>
            Future.successful(FirehoseResult.NotFound(event.atUri))
        }
      case None =>
        Future.successful(FirehoseResult.ValidationError(event.atUri, "Payload required"))
    }
  }

  private def deletePopulationBreakdown(event: PopulationBreakdownEvent): Future[FirehoseResult] = {
    populationBreakdownRepository.findByAtUri(event.atUri).flatMap {
      case Some(existing) =>
        populationBreakdownRepository.softDelete(existing.id.get).map {
          case true => FirehoseResult.Success(event.atUri, "", None, "Population Breakdown Deleted")
          case false => FirehoseResult.NotFound(event.atUri)
        }
      case None =>
        Future.successful(FirehoseResult.NotFound(event.atUri))
    }
  }

  // --- Haplogroup Reconciliation Handling ---

  private def handleHaplogroupReconciliation(event: HaplogroupReconciliationEvent): Future[FirehoseResult] = {
    event.action match {
      case FirehoseAction.Create => createHaplogroupReconciliation(event)
      case FirehoseAction.Update => updateHaplogroupReconciliation(event)
      case FirehoseAction.Delete => deleteHaplogroupReconciliation(event)
    }
  }

  private def createHaplogroupReconciliation(event: HaplogroupReconciliationEvent): Future[FirehoseResult] = {
    import play.api.libs.json.Json

    event.payload match {
      case Some(record) =>
        // Resolve specimen donor - could be by AT URI or identifier
        specimenDonorRepository.findByAtUri(record.specimenDonorRef).flatMap {
          case Some(donor) =>
            val dnaType = DnaType.fromString(record.dnaType).getOrElse(
              throw new IllegalArgumentException(s"Invalid DNA type: ${record.dnaType}")
            )

            val status = models.domain.genomics.ReconciliationStatus(
              compatibilityLevel = Some(record.status.compatibilityLevel),
              consensusHaplogroup = Some(record.status.consensusHaplogroup),
              statusConfidence = record.status.confidence,
              branchCompatibilityScore = record.status.branchCompatibilityScore,
              snpConcordance = record.status.snpConcordance,
              runCount = record.status.runCount,
              warnings = record.status.warnings
            )

            val reconciliation = HaplogroupReconciliation(
              id = None,
              atUri = Some(record.atUri),
              atCid = Some(UUID.randomUUID().toString),
              specimenDonorId = donor.id.get,
              dnaType = dnaType,
              status = status,
              runCalls = Json.toJson(record.runCalls),
              snpConflicts = record.snpConflicts.map(Json.toJson(_)),
              heteroplasmyObservations = record.heteroplasmyObservations.map(Json.toJson(_)),
              identityVerification = record.identityVerification.map(Json.toJson(_)),
              manualOverride = record.manualOverride.map(Json.toJson(_)),
              auditLog = record.auditLog.map(Json.toJson(_)),
              lastReconciliationAt = record.lastReconciliationAt.map(i => LocalDateTime.ofInstant(i, ZoneId.systemDefault())),
              deleted = false,
              createdAt = LocalDateTime.ofInstant(record.meta.createdAt, ZoneId.systemDefault()),
              updatedAt = LocalDateTime.now()
            )

            haplogroupReconciliationRepository.upsertBySpecimenDonorAndDnaType(reconciliation).map { created =>
              FirehoseResult.Success(event.atUri, created.atCid.getOrElse(""), None, "Haplogroup Reconciliation Created")
            }
          case None =>
            Future.successful(FirehoseResult.ValidationError(event.atUri, s"Specimen donor not found: ${record.specimenDonorRef}"))
        }
      case None =>
        Future.successful(FirehoseResult.ValidationError(event.atUri, "Payload required"))
    }
  }

  private def updateHaplogroupReconciliation(event: HaplogroupReconciliationEvent): Future[FirehoseResult] = {
    import play.api.libs.json.Json

    event.payload match {
      case Some(record) =>
        haplogroupReconciliationRepository.findByAtUri(record.atUri).flatMap {
          case Some(existing) =>
            val updatedStatus = existing.status.copy(
              compatibilityLevel = Some(record.status.compatibilityLevel),
              consensusHaplogroup = Some(record.status.consensusHaplogroup),
              statusConfidence = record.status.confidence.orElse(existing.status.statusConfidence),
              branchCompatibilityScore = record.status.branchCompatibilityScore.orElse(existing.status.branchCompatibilityScore),
              snpConcordance = record.status.snpConcordance.orElse(existing.status.snpConcordance),
              runCount = record.status.runCount.orElse(existing.status.runCount),
              warnings = record.status.warnings.orElse(existing.status.warnings)
            )

            val updated = existing.copy(
              status = updatedStatus,
              runCalls = Json.toJson(record.runCalls),
              snpConflicts = record.snpConflicts.map(Json.toJson(_)).orElse(existing.snpConflicts),
              heteroplasmyObservations = record.heteroplasmyObservations.map(Json.toJson(_)).orElse(existing.heteroplasmyObservations),
              identityVerification = record.identityVerification.map(Json.toJson(_)).orElse(existing.identityVerification),
              manualOverride = record.manualOverride.map(Json.toJson(_)).orElse(existing.manualOverride),
              auditLog = record.auditLog.map(Json.toJson(_)).orElse(existing.auditLog),
              lastReconciliationAt = record.lastReconciliationAt.map(i => LocalDateTime.ofInstant(i, ZoneId.systemDefault())).orElse(existing.lastReconciliationAt),
              atCid = Some(UUID.randomUUID().toString),
              updatedAt = LocalDateTime.now()
            )

            haplogroupReconciliationRepository.update(updated).map { success =>
              if (success) FirehoseResult.Success(event.atUri, updated.atCid.getOrElse(""), None, "Haplogroup Reconciliation Updated")
              else FirehoseResult.Conflict(event.atUri, "Update failed")
            }
          case None =>
            Future.successful(FirehoseResult.NotFound(event.atUri))
        }
      case None =>
        Future.successful(FirehoseResult.ValidationError(event.atUri, "Payload required"))
    }
  }

  private def deleteHaplogroupReconciliation(event: HaplogroupReconciliationEvent): Future[FirehoseResult] = {
    haplogroupReconciliationRepository.findByAtUri(event.atUri).flatMap {
      case Some(existing) =>
        haplogroupReconciliationRepository.softDelete(existing.id.get).map {
          case true => FirehoseResult.Success(event.atUri, "", None, "Haplogroup Reconciliation Deleted")
          case false => FirehoseResult.NotFound(event.atUri)
        }
      case None =>
        Future.successful(FirehoseResult.NotFound(event.atUri))
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

  // --- Instrument Observation Handling ---

  private def handleInstrumentObservation(event: InstrumentObservationEvent): Future[FirehoseResult] = {
    event.action match {
      case FirehoseAction.Create => createInstrumentObservation(event)
      case FirehoseAction.Update => updateInstrumentObservation(event)
      case FirehoseAction.Delete => deleteInstrumentObservation(event)
    }
  }

  private def createInstrumentObservation(event: InstrumentObservationEvent): Future[FirehoseResult] = {
    event.payload match {
      case Some(record) =>
        instrumentObservationRepository.findByAtUri(record.atUri).flatMap {
          case Some(_) =>
            Future.successful(FirehoseResult.Conflict(event.atUri, "Instrument observation already exists"))
          case None =>
            val observation = InstrumentObservation(
              atUri = record.atUri,
              atCid = event.atCid,
              instrumentId = record.instrumentId,
              labName = record.labName,
              biosampleRef = record.biosampleRef,
              sequenceRunRef = record.sequenceRunRef,
              platform = record.platform,
              instrumentModel = record.instrumentModel,
              flowcellId = record.flowcellId,
              runDate = record.runDate.map(i => LocalDateTime.ofInstant(i, ZoneId.systemDefault())),
              confidence = record.confidence
                .map(ObservationConfidence.fromString)
                .getOrElse(ObservationConfidence.Inferred)
            )
            instrumentObservationRepository.create(observation).map { created =>
              logger.info(s"Created instrument observation for instrument ${record.instrumentId} at lab ${record.labName}")
              FirehoseResult.Success(event.atUri, UUID.randomUUID().toString, None, "Instrument observation created")
            }
        }
      case None =>
        Future.successful(FirehoseResult.ValidationError(event.atUri, "Missing payload for InstrumentObservationEvent"))
    }
  }

  private def updateInstrumentObservation(event: InstrumentObservationEvent): Future[FirehoseResult] = {
    event.payload match {
      case Some(record) =>
        instrumentObservationRepository.findByAtUri(event.atUri).flatMap {
          case Some(existing) =>
            val updated = existing.copy(
              atCid = event.atCid,
              instrumentId = record.instrumentId,
              labName = record.labName,
              biosampleRef = record.biosampleRef,
              sequenceRunRef = record.sequenceRunRef,
              platform = record.platform,
              instrumentModel = record.instrumentModel,
              flowcellId = record.flowcellId,
              runDate = record.runDate.map(i => LocalDateTime.ofInstant(i, ZoneId.systemDefault())),
              confidence = record.confidence
                .map(ObservationConfidence.fromString)
                .getOrElse(existing.confidence)
            )
            instrumentObservationRepository.update(updated).map { success =>
              if (success) FirehoseResult.Success(event.atUri, UUID.randomUUID().toString, None, "Instrument observation updated")
              else FirehoseResult.Error(event.atUri, "Failed to update instrument observation")
            }
          case None =>
            Future.successful(FirehoseResult.NotFound(event.atUri))
        }
      case None =>
        Future.successful(FirehoseResult.ValidationError(event.atUri, "Missing payload for InstrumentObservationEvent"))
    }
  }

  private def deleteInstrumentObservation(event: InstrumentObservationEvent): Future[FirehoseResult] = {
    instrumentObservationRepository.deleteByAtUri(event.atUri).map { deleted =>
      if (deleted) FirehoseResult.Success(event.atUri, "", None, "Instrument observation deleted")
      else FirehoseResult.NotFound(event.atUri)
    }
  }

  // --- Group Project Handling ---

  private def handleGroupProject(event: GroupProjectEvent): Future[FirehoseResult] = {
    event.action match {
      case FirehoseAction.Create => createGroupProject(event)
      case FirehoseAction.Update => updateGroupProject(event)
      case FirehoseAction.Delete => deleteGroupProject(event)
    }
  }

  private def createGroupProject(event: GroupProjectEvent): Future[FirehoseResult] = {
    event.payload match {
      case Some(record) =>
        val ownerDid = record.governance.administrators.headOption.map(_.citizenDid).getOrElse("")
        if (ownerDid.isEmpty) {
          Future.successful(FirehoseResult.ValidationError(event.atUri, "At least one administrator is required"))
        } else {
          val newAtCid = UUID.randomUUID().toString
          val project = GroupProject(
            projectName = record.projectName,
            projectType = record.projectType,
            targetHaplogroup = record.targetHaplogroup,
            targetLineage = record.targetLineage,
            description = record.description,
            backgroundInfo = record.backgroundInfo,
            joinPolicy = record.joinPolicy.getOrElse("APPROVAL_REQUIRED"),
            haplogroupRequirement = record.haplogroupRequirement,
            memberListVisibility = record.visibilityPolicy.flatMap(_.memberListVisibility).getOrElse("MEMBERS_ONLY"),
            strPolicy = record.visibilityPolicy.flatMap(_.strPolicy).getOrElse("DISTANCE_ONLY"),
            snpPolicy = record.visibilityPolicy.flatMap(_.snpPolicy).getOrElse("TERMINAL_ONLY"),
            publicTreeView = record.visibilityPolicy.flatMap(_.publicTreeView).getOrElse(false),
            successionPolicy = record.governance.successionPolicy,
            ownerDid = ownerDid,
            atUri = Some(record.atUri),
            atCid = Some(newAtCid),
            createdAt = LocalDateTime.ofInstant(record.meta.createdAt, ZoneId.systemDefault()),
            updatedAt = LocalDateTime.now()
          )
          groupProjectRepository.create(project).flatMap { created =>
            val adminMember = GroupProjectMember(
              groupProjectId = created.id.get,
              citizenDid = ownerDid,
              role = "ADMIN",
              status = "ACTIVE",
              joinedAt = Some(LocalDateTime.now())
            )
            groupProjectMemberRepository.create(adminMember).map { _ =>
              FirehoseResult.Success(event.atUri, newAtCid, None, "Group Project Created")
            }
          }
        }
      case None =>
        Future.successful(FirehoseResult.ValidationError(event.atUri, "Payload required"))
    }
  }

  private def updateGroupProject(event: GroupProjectEvent): Future[FirehoseResult] = {
    event.payload match {
      case Some(record) =>
        groupProjectRepository.findByAtUri(event.atUri).flatMap {
          case Some(existing) =>
            val updated = existing.copy(
              projectName = record.projectName,
              description = record.description,
              backgroundInfo = record.backgroundInfo,
              joinPolicy = record.joinPolicy.getOrElse(existing.joinPolicy),
              haplogroupRequirement = record.haplogroupRequirement.orElse(existing.haplogroupRequirement),
              memberListVisibility = record.visibilityPolicy.flatMap(_.memberListVisibility).getOrElse(existing.memberListVisibility),
              strPolicy = record.visibilityPolicy.flatMap(_.strPolicy).getOrElse(existing.strPolicy),
              snpPolicy = record.visibilityPolicy.flatMap(_.snpPolicy).getOrElse(existing.snpPolicy),
              publicTreeView = record.visibilityPolicy.flatMap(_.publicTreeView).getOrElse(existing.publicTreeView),
              successionPolicy = record.governance.successionPolicy.orElse(existing.successionPolicy),
              atCid = Some(UUID.randomUUID().toString)
            )
            groupProjectRepository.update(updated).map {
              case true => FirehoseResult.Success(event.atUri, updated.atCid.getOrElse(""), None, "Group Project Updated")
              case false => FirehoseResult.Conflict(event.atUri, "Update failed")
            }
          case None =>
            Future.successful(FirehoseResult.NotFound(event.atUri))
        }
      case None =>
        Future.successful(FirehoseResult.ValidationError(event.atUri, "Payload required"))
    }
  }

  private def deleteGroupProject(event: GroupProjectEvent): Future[FirehoseResult] = {
    groupProjectRepository.softDeleteByAtUri(event.atUri).map {
      case true => FirehoseResult.Success(event.atUri, "", None, "Group Project Deleted")
      case false => FirehoseResult.NotFound(event.atUri)
    }
  }

  // --- Project Membership Handling ---

  private def handleProjectMembership(event: ProjectMembershipEvent): Future[FirehoseResult] = {
    event.action match {
      case FirehoseAction.Create => createProjectMembership(event)
      case FirehoseAction.Update => updateProjectMembership(event)
      case FirehoseAction.Delete => deleteProjectMembership(event)
    }
  }

  private def createProjectMembership(event: ProjectMembershipEvent): Future[FirehoseResult] = {
    event.payload match {
      case Some(record) =>
        groupProjectRepository.findByAtUri(record.projectRef).flatMap {
          case Some(project) =>
            val status = if (project.joinPolicy == "OPEN") "ACTIVE" else record.status
            val member = GroupProjectMember(
              groupProjectId = project.id.get,
              citizenDid = extractDidFromAtUri(record.atUri),
              biosampleAtUri = Some(record.biosampleRef),
              status = status,
              displayName = record.displayName,
              kitId = record.kitId,
              subgroupIds = record.subgroupAssignments.getOrElse(Seq.empty).toList,
              contributionLevel = record.contributionLevel,
              joinedAt = if (status == "ACTIVE") Some(LocalDateTime.now()) else None,
              atUri = Some(record.atUri),
              atCid = Some(UUID.randomUUID().toString)
            )
            groupProjectMemberRepository.create(member).map { created =>
              FirehoseResult.Success(event.atUri, created.atCid.getOrElse(""), None, "Project Membership Created")
            }
          case None =>
            Future.successful(FirehoseResult.ValidationError(event.atUri, s"Group project not found: ${record.projectRef}"))
        }
      case None =>
        Future.successful(FirehoseResult.ValidationError(event.atUri, "Payload required"))
    }
  }

  private def updateProjectMembership(event: ProjectMembershipEvent): Future[FirehoseResult] = {
    event.payload match {
      case Some(record) =>
        groupProjectMemberRepository.findByAtUri(event.atUri).flatMap {
          case Some(existing) =>
            val updated = existing.copy(
              status = record.status,
              displayName = record.displayName.orElse(existing.displayName),
              kitId = record.kitId.orElse(existing.kitId),
              subgroupIds = record.subgroupAssignments.map(_.toList).getOrElse(existing.subgroupIds),
              contributionLevel = record.contributionLevel.orElse(existing.contributionLevel),
              atCid = Some(UUID.randomUUID().toString)
            )
            groupProjectMemberRepository.update(updated).map {
              case true => FirehoseResult.Success(event.atUri, updated.atCid.getOrElse(""), None, "Project Membership Updated")
              case false => FirehoseResult.Conflict(event.atUri, "Update failed")
            }
          case None =>
            Future.successful(FirehoseResult.NotFound(event.atUri))
        }
      case None =>
        Future.successful(FirehoseResult.ValidationError(event.atUri, "Payload required"))
    }
  }

  private def deleteProjectMembership(event: ProjectMembershipEvent): Future[FirehoseResult] = {
    groupProjectMemberRepository.findByAtUri(event.atUri).flatMap {
      case Some(existing) =>
        groupProjectMemberRepository.updateStatus(existing.id.get, "LEFT").map {
          case true => FirehoseResult.Success(event.atUri, "", None, "Project Membership Removed")
          case false => FirehoseResult.NotFound(event.atUri)
        }
      case None =>
        Future.successful(FirehoseResult.NotFound(event.atUri))
    }
  }

  private def extractDidFromAtUri(atUri: String): String = {
    // AT URI format: at://{DID}/{collection}/{rkey}
    atUri.stripPrefix("at://").split("/").headOption.getOrElse("")
  }

}