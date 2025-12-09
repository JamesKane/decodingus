package services.firehose

import models.atmosphere.{
  RecordMeta, GenotypeRecord, PopulationBreakdownRecord, HaplogroupReconciliationRecord,
  ProjectRecord, PopulationComponent => AtmospherePopulationComponent,
  SuperPopulationSummary => AtmosphereSuperPopulationSummary,
  ReconciliationStatus => AtmosphereReconciliationStatus,
  RunHaplogroupCall, IdentityVerification => AtmosphereIdentityVerification
}
import models.domain.Project
import models.domain.genomics.*
import org.mockito.ArgumentMatchers.{any, anyString}
import org.mockito.Mockito.{never, verify, when}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.play.PlaySpec
import play.api.libs.json.Json
import repositories.*
import services.TestTypeService

import java.time.{Instant, LocalDateTime}
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

class AtmosphereEventHandlerSpec extends PlaySpec with MockitoSugar with ScalaFutures {

  implicit val ec: ExecutionContext = ExecutionContext.global

  // --- Test Fixtures ---

  def createRecordMeta(version: Int = 1): RecordMeta = RecordMeta(
    version = version,
    createdAt = Instant.now(),
    updatedAt = Some(Instant.now()),
    lastModifiedField = None
  )

  def createMocks(): (
    CitizenBiosampleRepository,
    SequenceLibraryRepository,
    SequenceFileRepository,
    AlignmentRepository,
    SpecimenDonorRepository,
    ProjectRepository,
    TestTypeService,
    GenotypeDataRepository,
    PopulationBreakdownRepository,
    HaplogroupReconciliationRepository
  ) = (
    mock[CitizenBiosampleRepository],
    mock[SequenceLibraryRepository],
    mock[SequenceFileRepository],
    mock[AlignmentRepository],
    mock[SpecimenDonorRepository],
    mock[ProjectRepository],
    mock[TestTypeService],
    mock[GenotypeDataRepository],
    mock[PopulationBreakdownRepository],
    mock[HaplogroupReconciliationRepository]
  )

  def createHandler(mocks: (
    CitizenBiosampleRepository,
    SequenceLibraryRepository,
    SequenceFileRepository,
    AlignmentRepository,
    SpecimenDonorRepository,
    ProjectRepository,
    TestTypeService,
    GenotypeDataRepository,
    PopulationBreakdownRepository,
    HaplogroupReconciliationRepository
  )): AtmosphereEventHandler = {
    val (biosampleRepo, seqLibRepo, seqFileRepo, alignmentRepo, donorRepo, projectRepo, testTypeService, genotypeRepo, popRepo, reconRepo) = mocks
    new AtmosphereEventHandler(
      biosampleRepo,
      seqLibRepo,
      seqFileRepo,
      alignmentRepo,
      donorRepo,
      projectRepo,
      testTypeService,
      genotypeRepo,
      popRepo,
      reconRepo
    )
  }

  // ==================== GENOTYPE TESTS ====================

  "AtmosphereEventHandler - Genotype" should {

    "create a new genotype successfully" in {
      val mocks = createMocks()
      val (biosampleRepo, _, _, _, _, _, testTypeService, genotypeRepo, _, _) = mocks

      val atUri = "at://did:plc:test123/com.decodingus.atmosphere.genotype/rkey1"
      val biosampleAtUri = "at://did:plc:test123/com.decodingus.atmosphere.biosample/sample1"
      val sampleGuid = UUID.randomUUID()

      val record = GenotypeRecord(
        atUri = atUri,
        meta = createRecordMeta(),
        biosampleRef = biosampleAtUri,
        testTypeCode = "ARRAY_23ANDME_V5",
        provider = "23andMe",
        chipType = None,
        chipVersion = Some("v5"),
        totalMarkersCalled = Some(600000),
        totalMarkersPossible = Some(650000),
        callRate = Some(0.923),
        noCallRate = Some(0.077),
        yMarkersCalled = Some(3500),
        yMarkersTotal = Some(4000),
        mtMarkersCalled = Some(3000),
        mtMarkersTotal = Some(3200),
        autosomalMarkersCalled = Some(590000),
        hetRate = Some(0.32),
        testDate = Some(Instant.now()),
        processedAt = Some(Instant.now()),
        buildVersion = Some("GRCh37"),
        sourceFileHash = Some("abc123hash"),
        derivedHaplogroups = None,
        populationBreakdownRef = None,
        files = None,
        imputationRef = None
      )

      val event = GenotypeEvent(
        atUri = atUri,
        atCid = None,
        action = FirehoseAction.Create,
        payload = Some(record)
      )

      // Mock biosample lookup
      val biosample = CitizenBiosample(
        id = Some(1),
        atUri = Some(biosampleAtUri),
        accession = Some("SAMPLE-001"),
        alias = None,
        sourcePlatform = None,
        collectionDate = None,
        sex = None,
        geocoord = None,
        description = None,
        sampleGuid = sampleGuid,
        deleted = false,
        createdAt = LocalDateTime.now(),
        updatedAt = LocalDateTime.now()
      )
      when(biosampleRepo.findByAtUri(biosampleAtUri)).thenReturn(Future.successful(Some(biosample)))

      // Mock test type lookup
      val testType = TestTypeRow(
        id = Some(1),
        code = "ARRAY_23ANDME_V5",
        displayName = "23andMe v5",
        category = DataGenerationMethod.Genotyping,
        targetType = TargetType.WholeGenome,
        supportsHaplogroupY = true,
        supportsHaplogroupMt = true,
        supportsAutosomalIbd = true,
        supportsAncestry = true,
        typicalFileFormats = List("TXT", "CSV")
      )
      when(testTypeService.getByCode("ARRAY_23ANDME_V5")).thenReturn(Future.successful(Some(testType)))

      // Mock genotype creation
      when(genotypeRepo.create(any[GenotypeData])).thenAnswer(new Answer[Future[GenotypeData]] {
        override def answer(invocation: InvocationOnMock): Future[GenotypeData] = {
          val data = invocation.getArgument[GenotypeData](0)
          Future.successful(data.copy(id = Some(100)))
        }
      })

      val handler = createHandler(mocks)

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.Success]
        val success = result.asInstanceOf[FirehoseResult.Success]
        success.message must include("Genotype Created")
        success.sampleGuid mustBe Some(sampleGuid)

        verify(biosampleRepo).findByAtUri(biosampleAtUri)
        verify(testTypeService).getByCode("ARRAY_23ANDME_V5")
        verify(genotypeRepo).create(any[GenotypeData])
      }
    }

    "return validation error when biosample not found for genotype" in {
      val mocks = createMocks()
      val (biosampleRepo, _, _, _, _, _, _, _, _, _) = mocks

      val atUri = "at://did:plc:test123/com.decodingus.atmosphere.genotype/rkey1"
      val biosampleAtUri = "at://did:plc:test123/com.decodingus.atmosphere.biosample/nonexistent"

      val record = GenotypeRecord(
        atUri = atUri,
        meta = createRecordMeta(),
        biosampleRef = biosampleAtUri,
        testTypeCode = "ARRAY_23ANDME_V5",
        provider = "23andMe",
        chipType = None,
        chipVersion = None,
        totalMarkersCalled = None,
        totalMarkersPossible = None,
        callRate = None,
        noCallRate = None,
        yMarkersCalled = None,
        yMarkersTotal = None,
        mtMarkersCalled = None,
        mtMarkersTotal = None,
        autosomalMarkersCalled = None,
        hetRate = None,
        testDate = None,
        processedAt = None,
        buildVersion = None,
        sourceFileHash = None,
        derivedHaplogroups = None,
        populationBreakdownRef = None,
        files = None,
        imputationRef = None
      )

      val event = GenotypeEvent(
        atUri = atUri,
        atCid = None,
        action = FirehoseAction.Create,
        payload = Some(record)
      )

      when(biosampleRepo.findByAtUri(biosampleAtUri)).thenReturn(Future.successful(None))

      val handler = createHandler(mocks)

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.ValidationError]
        result.asInstanceOf[FirehoseResult.ValidationError].message must include("Biosample not found")
      }
    }

    "return validation error when test type code is invalid" in {
      val mocks = createMocks()
      val (biosampleRepo, _, _, _, _, _, testTypeService, _, _, _) = mocks

      val atUri = "at://did:plc:test123/com.decodingus.atmosphere.genotype/rkey1"
      val biosampleAtUri = "at://did:plc:test123/com.decodingus.atmosphere.biosample/sample1"
      val sampleGuid = UUID.randomUUID()

      val record = GenotypeRecord(
        atUri = atUri,
        meta = createRecordMeta(),
        biosampleRef = biosampleAtUri,
        testTypeCode = "INVALID_TEST_TYPE",
        provider = "Unknown",
        chipType = None,
        chipVersion = None,
        totalMarkersCalled = None,
        totalMarkersPossible = None,
        callRate = None,
        noCallRate = None,
        yMarkersCalled = None,
        yMarkersTotal = None,
        mtMarkersCalled = None,
        mtMarkersTotal = None,
        autosomalMarkersCalled = None,
        hetRate = None,
        testDate = None,
        processedAt = None,
        buildVersion = None,
        sourceFileHash = None,
        derivedHaplogroups = None,
        populationBreakdownRef = None,
        files = None,
        imputationRef = None
      )

      val event = GenotypeEvent(
        atUri = atUri,
        atCid = None,
        action = FirehoseAction.Create,
        payload = Some(record)
      )

      val biosample = CitizenBiosample(
        id = Some(1),
        atUri = Some(biosampleAtUri),
        accession = Some("SAMPLE-001"),
        alias = None,
        sourcePlatform = None,
        collectionDate = None,
        sex = None,
        geocoord = None,
        description = None,
        sampleGuid = sampleGuid,
        deleted = false,
        createdAt = LocalDateTime.now(),
        updatedAt = LocalDateTime.now()
      )
      when(biosampleRepo.findByAtUri(biosampleAtUri)).thenReturn(Future.successful(Some(biosample)))
      when(testTypeService.getByCode("INVALID_TEST_TYPE")).thenReturn(Future.successful(None))

      val handler = createHandler(mocks)

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.ValidationError]
        result.asInstanceOf[FirehoseResult.ValidationError].message must include("Invalid test type code")
      }
    }

    "delete genotype successfully" in {
      val mocks = createMocks()
      val (_, _, _, _, _, _, _, genotypeRepo, _, _) = mocks

      val atUri = "at://did:plc:test123/com.decodingus.atmosphere.genotype/rkey1"
      val sampleGuid = UUID.randomUUID()

      val existing = GenotypeData(
        id = Some(100),
        atUri = Some(atUri),
        atCid = Some("cid123"),
        sampleGuid = sampleGuid,
        testTypeId = Some(1),
        provider = Some("23andMe"),
        chipVersion = None,
        buildVersion = None,
        sourceFileHash = None,
        metrics = GenotypeMetrics(),
        populationBreakdownId = None,
        deleted = false,
        createdAt = LocalDateTime.now(),
        updatedAt = LocalDateTime.now()
      )

      val event = GenotypeEvent(
        atUri = atUri,
        atCid = None,
        action = FirehoseAction.Delete,
        payload = None
      )

      when(genotypeRepo.findByAtUri(atUri)).thenReturn(Future.successful(Some(existing)))
      when(genotypeRepo.softDelete(100)).thenReturn(Future.successful(true))

      val handler = createHandler(mocks)

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.Success]
        verify(genotypeRepo).softDelete(100)
      }
    }
  }

  // ==================== POPULATION BREAKDOWN TESTS ====================

  "AtmosphereEventHandler - PopulationBreakdown" should {

    "create a population breakdown with components successfully" in {
      val mocks = createMocks()
      val (biosampleRepo, _, _, _, _, _, _, _, popRepo, _) = mocks

      val atUri = "at://did:plc:test123/com.decodingus.atmosphere.populationBreakdown/rkey1"
      val biosampleAtUri = "at://did:plc:test123/com.decodingus.atmosphere.biosample/sample1"
      val sampleGuid = UUID.randomUUID()

      val record = PopulationBreakdownRecord(
        atUri = atUri,
        meta = createRecordMeta(),
        biosampleRef = biosampleAtUri,
        analysisMethod = "PCA_PROJECTION_GMM",
        panelType = Some("aims"),
        referencePopulations = Some("1000G_HGDP_v1"),
        referenceVersion = Some("v1.0"),
        kValue = Some(33),
        snpsAnalyzed = Some(5000),
        snpsWithGenotype = Some(4800),
        snpsMissing = Some(200),
        confidenceLevel = Some(0.95),
        pcaCoordinates = Some(Seq(0.5, -0.3, 0.1)),
        components = Seq(
          AtmospherePopulationComponent("GBR", Some("British"), Some("European"), 45.5, Some(Map("lower" -> 40.0, "upper" -> 51.0)), Some(1)),
          AtmospherePopulationComponent("IBS", Some("Iberian"), Some("European"), 25.3, Some(Map("lower" -> 20.0, "upper" -> 30.0)), Some(2)),
          AtmospherePopulationComponent("TSI", Some("Tuscan"), Some("European"), 15.2, None, Some(3))
        ),
        superPopulationSummary = Some(Seq(
          AtmosphereSuperPopulationSummary("European", 86.0, Seq("GBR", "IBS", "TSI"))
        )),
        analysisDate = Some(Instant.now()),
        pipelineVersion = Some("1.0.0")
      )

      val event = PopulationBreakdownEvent(
        atUri = atUri,
        atCid = None,
        action = FirehoseAction.Create,
        payload = Some(record)
      )

      // Mock biosample lookup
      val biosample = CitizenBiosample(
        id = Some(1),
        atUri = Some(biosampleAtUri),
        accession = Some("SAMPLE-001"),
        alias = None,
        sourcePlatform = None,
        collectionDate = None,
        sex = None,
        geocoord = None,
        description = None,
        sampleGuid = sampleGuid,
        deleted = false,
        createdAt = LocalDateTime.now(),
        updatedAt = LocalDateTime.now()
      )
      when(biosampleRepo.findByAtUri(biosampleAtUri)).thenReturn(Future.successful(Some(biosample)))

      // Mock population breakdown creation
      when(popRepo.create(any[PopulationBreakdown])).thenAnswer(new Answer[Future[PopulationBreakdown]] {
        override def answer(invocation: InvocationOnMock): Future[PopulationBreakdown] = {
          val data = invocation.getArgument[PopulationBreakdown](0)
          Future.successful(data.copy(id = Some(100)))
        }
      })

      // Mock component upsert
      when(popRepo.upsertComponentsByBreakdownId(any[Int], any[Seq[models.domain.genomics.PopulationComponent]])).thenReturn(Future.successful(Seq.empty))
      when(popRepo.upsertSummariesByBreakdownId(any[Int], any[Seq[models.domain.genomics.SuperPopulationSummary]])).thenReturn(Future.successful(Seq.empty))

      val handler = createHandler(mocks)

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.Success]
        val success = result.asInstanceOf[FirehoseResult.Success]
        success.message must include("Population Breakdown Created")
        success.sampleGuid mustBe Some(sampleGuid)

        verify(biosampleRepo).findByAtUri(biosampleAtUri)
        verify(popRepo).create(any[PopulationBreakdown])
        verify(popRepo).upsertComponentsByBreakdownId(any[Int], any[Seq[models.domain.genomics.PopulationComponent]])
        verify(popRepo).upsertSummariesByBreakdownId(any[Int], any[Seq[models.domain.genomics.SuperPopulationSummary]])
      }
    }

    "return validation error when biosample not found for population breakdown" in {
      val mocks = createMocks()
      val (biosampleRepo, _, _, _, _, _, _, _, _, _) = mocks

      val atUri = "at://did:plc:test123/com.decodingus.atmosphere.populationBreakdown/rkey1"
      val biosampleAtUri = "at://did:plc:test123/com.decodingus.atmosphere.biosample/nonexistent"

      val record = PopulationBreakdownRecord(
        atUri = atUri,
        meta = createRecordMeta(),
        biosampleRef = biosampleAtUri,
        analysisMethod = "PCA_PROJECTION_GMM",
        panelType = None,
        referencePopulations = None,
        referenceVersion = None,
        kValue = None,
        snpsAnalyzed = None,
        snpsWithGenotype = None,
        snpsMissing = None,
        confidenceLevel = None,
        pcaCoordinates = None,
        components = Seq.empty,
        superPopulationSummary = None,
        analysisDate = None,
        pipelineVersion = None
      )

      val event = PopulationBreakdownEvent(
        atUri = atUri,
        atCid = None,
        action = FirehoseAction.Create,
        payload = Some(record)
      )

      when(biosampleRepo.findByAtUri(biosampleAtUri)).thenReturn(Future.successful(None))

      val handler = createHandler(mocks)

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.ValidationError]
        result.asInstanceOf[FirehoseResult.ValidationError].message must include("Biosample not found")
      }
    }

    "delete population breakdown successfully" in {
      val mocks = createMocks()
      val (_, _, _, _, _, _, _, _, popRepo, _) = mocks

      val atUri = "at://did:plc:test123/com.decodingus.atmosphere.populationBreakdown/rkey1"
      val sampleGuid = UUID.randomUUID()

      val existing = PopulationBreakdown(
        id = Some(100),
        atUri = Some(atUri),
        atCid = Some("cid123"),
        sampleGuid = sampleGuid,
        analysisMethod = "PCA_PROJECTION_GMM",
        panelType = None,
        referencePopulations = None,
        referenceVersion = None,
        snpsAnalyzed = None,
        snpsWithGenotype = None,
        snpsMissing = None,
        confidenceLevel = None,
        pcaCoordinates = None,
        analysisDate = None,
        pipelineVersion = None,
        deleted = false,
        createdAt = LocalDateTime.now(),
        updatedAt = LocalDateTime.now()
      )

      val event = PopulationBreakdownEvent(
        atUri = atUri,
        atCid = None,
        action = FirehoseAction.Delete,
        payload = None
      )

      when(popRepo.findByAtUri(atUri)).thenReturn(Future.successful(Some(existing)))
      when(popRepo.softDelete(100)).thenReturn(Future.successful(true))

      val handler = createHandler(mocks)

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.Success]
        verify(popRepo).softDelete(100)
      }
    }
  }

  // ==================== HAPLOGROUP RECONCILIATION TESTS ====================

  "AtmosphereEventHandler - HaplogroupReconciliation" should {

    "create a haplogroup reconciliation successfully" in {
      val mocks = createMocks()
      val (_, _, _, _, donorRepo, _, _, _, _, reconRepo) = mocks

      val atUri = "at://did:plc:test123/com.decodingus.atmosphere.haplogroupReconciliation/rkey1"
      val donorAtUri = "at://did:plc:test123/specimen-donor/donor1"

      val record = HaplogroupReconciliationRecord(
        atUri = atUri,
        meta = createRecordMeta(),
        specimenDonorRef = donorAtUri,
        dnaType = "Y_DNA",
        status = AtmosphereReconciliationStatus(
          compatibilityLevel = "COMPATIBLE",
          consensusHaplogroup = "R-BY18291",
          confidence = Some(0.98),
          divergencePoint = None,
          branchCompatibilityScore = Some(0.95),
          snpConcordance = Some(0.99),
          runCount = Some(2),
          warnings = None
        ),
        runCalls = Seq(
          RunHaplogroupCall(
            sourceRef = "at://run1",
            haplogroup = "R-BY18291",
            confidence = 0.97,
            callMethod = "SNP_PHYLOGENETIC",
            score = Some(0.97),
            supportingSnps = Some(500),
            conflictingSnps = Some(2),
            noCalls = Some(10),
            technology = Some("WGS"),
            meanCoverage = Some(30.0),
            treeVersion = Some("ISOGG2024"),
            strPrediction = None
          ),
          RunHaplogroupCall(
            sourceRef = "at://run2",
            haplogroup = "R-BY18291",
            confidence = 0.95,
            callMethod = "SNP_PHYLOGENETIC",
            score = Some(0.95),
            supportingSnps = Some(450),
            conflictingSnps = Some(5),
            noCalls = Some(15),
            technology = Some("BIG_Y"),
            meanCoverage = Some(100.0),
            treeVersion = Some("ISOGG2024"),
            strPrediction = None
          )
        ),
        snpConflicts = None,
        heteroplasmyObservations = None,
        identityVerification = Some(AtmosphereIdentityVerification(
          kinshipCoefficient = Some(0.5),
          fingerprintSnpConcordance = Some(0.999),
          yStrDistance = Some(0),
          verificationStatus = Some("VERIFIED_SAME"),
          verificationMethod = Some("Y_STR")
        )),
        lastReconciliationAt = Some(Instant.now()),
        manualOverride = None,
        auditLog = None
      )

      val event = HaplogroupReconciliationEvent(
        atUri = atUri,
        atCid = None,
        action = FirehoseAction.Create,
        payload = Some(record)
      )

      // Mock donor lookup
      val donor = SpecimenDonor(
        id = Some(1),
        donorIdentifier = "DONOR-001",
        originBiobank = "TestLab",
        donorType = BiosampleType.Citizen,
        sex = Some(BiologicalSex.Male),
        geocoord = None,
        atUri = Some(donorAtUri)
      )
      when(donorRepo.findByAtUri(donorAtUri)).thenReturn(Future.successful(Some(donor)))

      // Mock reconciliation upsert
      when(reconRepo.upsertBySpecimenDonorAndDnaType(any[HaplogroupReconciliation])).thenAnswer(new Answer[Future[HaplogroupReconciliation]] {
        override def answer(invocation: InvocationOnMock): Future[HaplogroupReconciliation] = {
          val data = invocation.getArgument[HaplogroupReconciliation](0)
          Future.successful(data.copy(id = Some(100)))
        }
      })

      val handler = createHandler(mocks)

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.Success]
        val success = result.asInstanceOf[FirehoseResult.Success]
        success.message must include("Haplogroup Reconciliation Created")

        verify(donorRepo).findByAtUri(donorAtUri)
        verify(reconRepo).upsertBySpecimenDonorAndDnaType(any[HaplogroupReconciliation])
      }
    }

    "return validation error when specimen donor not found" in {
      val mocks = createMocks()
      val (_, _, _, _, donorRepo, _, _, _, _, _) = mocks

      val atUri = "at://did:plc:test123/com.decodingus.atmosphere.haplogroupReconciliation/rkey1"
      val donorAtUri = "at://did:plc:test123/specimen-donor/nonexistent"

      val record = HaplogroupReconciliationRecord(
        atUri = atUri,
        meta = createRecordMeta(),
        specimenDonorRef = donorAtUri,
        dnaType = "Y_DNA",
        status = AtmosphereReconciliationStatus(
          compatibilityLevel = "COMPATIBLE",
          consensusHaplogroup = "R-M269",
          confidence = None,
          divergencePoint = None,
          branchCompatibilityScore = None,
          snpConcordance = None,
          runCount = None,
          warnings = None
        ),
        runCalls = Seq.empty,
        snpConflicts = None,
        heteroplasmyObservations = None,
        identityVerification = None,
        lastReconciliationAt = None,
        manualOverride = None,
        auditLog = None
      )

      val event = HaplogroupReconciliationEvent(
        atUri = atUri,
        atCid = None,
        action = FirehoseAction.Create,
        payload = Some(record)
      )

      when(donorRepo.findByAtUri(donorAtUri)).thenReturn(Future.successful(None))

      val handler = createHandler(mocks)

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.ValidationError]
        result.asInstanceOf[FirehoseResult.ValidationError].message must include("Specimen donor not found")
      }
    }

    "delete haplogroup reconciliation successfully" in {
      val mocks = createMocks()
      val (_, _, _, _, _, _, _, _, _, reconRepo) = mocks

      val atUri = "at://did:plc:test123/com.decodingus.atmosphere.haplogroupReconciliation/rkey1"

      val existing = HaplogroupReconciliation(
        id = Some(100),
        atUri = Some(atUri),
        atCid = Some("cid123"),
        specimenDonorId = 1,
        dnaType = DnaType.Y_DNA,
        status = models.domain.genomics.ReconciliationStatus(
          compatibilityLevel = Some("COMPATIBLE"),
          consensusHaplogroup = Some("R-M269"),
          statusConfidence = None,
          branchCompatibilityScore = None,
          snpConcordance = None,
          runCount = None,
          warnings = None
        ),
        runCalls = Json.arr(),
        snpConflicts = None,
        heteroplasmyObservations = None,
        identityVerification = None,
        manualOverride = None,
        auditLog = None,
        lastReconciliationAt = None,
        deleted = false,
        createdAt = LocalDateTime.now(),
        updatedAt = LocalDateTime.now()
      )

      val event = HaplogroupReconciliationEvent(
        atUri = atUri,
        atCid = None,
        action = FirehoseAction.Delete,
        payload = None
      )

      when(reconRepo.findByAtUri(atUri)).thenReturn(Future.successful(Some(existing)))
      when(reconRepo.softDelete(100)).thenReturn(Future.successful(true))

      val handler = createHandler(mocks)

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.Success]
        verify(reconRepo).softDelete(100)
      }
    }
  }

  // ==================== PROJECT TESTS ====================

  "AtmosphereEventHandler - Project" should {

    "create a project successfully" in {
      val mocks = createMocks()
      val (_, _, _, _, _, projectRepo, _, _, _, _) = mocks

      val atUri = "at://did:plc:admin/com.decodingus.atmosphere.project/rkey1"

      val record = ProjectRecord(
        atUri = atUri,
        meta = createRecordMeta(),
        projectName = "Test Research Project",
        description = Some("A test project for testing"),
        administrator = "did:plc:admin",
        memberRefs = Seq("did:plc:member1", "did:plc:member2")
      )

      val event = AtmosphereProjectEvent(
        atUri = atUri,
        atCid = None,
        action = FirehoseAction.Create,
        payload = Some(record)
      )

      when(projectRepo.create(any[Project])).thenAnswer(new Answer[Future[Project]] {
        override def answer(invocation: InvocationOnMock): Future[Project] = {
          val proj = invocation.getArgument[Project](0)
          Future.successful(proj.copy(id = Some(100)))
        }
      })

      val handler = createHandler(mocks)

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.Success]
        val success = result.asInstanceOf[FirehoseResult.Success]
        success.message must include("Project Created")

        verify(projectRepo).create(any[Project])
      }
    }

    "delete project successfully" in {
      val mocks = createMocks()
      val (_, _, _, _, _, projectRepo, _, _, _, _) = mocks

      val atUri = "at://did:plc:admin/com.decodingus.atmosphere.project/rkey1"

      val event = AtmosphereProjectEvent(
        atUri = atUri,
        atCid = None,
        action = FirehoseAction.Delete,
        payload = None
      )

      when(projectRepo.softDeleteByAtUri(atUri)).thenReturn(Future.successful(true))

      val handler = createHandler(mocks)

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.Success]
        verify(projectRepo).softDeleteByAtUri(atUri)
      }
    }
  }

  // ==================== VALIDATION ERROR TESTS ====================

  "AtmosphereEventHandler - Validation" should {

    "return validation error when payload is missing for create" in {
      val mocks = createMocks()

      val atUri = "at://did:plc:test123/com.decodingus.atmosphere.genotype/rkey1"

      val event = GenotypeEvent(
        atUri = atUri,
        atCid = None,
        action = FirehoseAction.Create,
        payload = None
      )

      val handler = createHandler(mocks)

      whenReady(handler.handle(event)) { result =>
        result mustBe a[FirehoseResult.ValidationError]
        result.asInstanceOf[FirehoseResult.ValidationError].message must include("Payload required")
      }
    }
  }
}
