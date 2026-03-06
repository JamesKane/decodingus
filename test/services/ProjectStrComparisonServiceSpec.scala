package services

import helpers.ServiceSpec
import models.dal.domain.genomics.{BiosampleVariantCall, StrMutationRate}
import models.domain.{EffectiveVisibility, GroupProject, GroupProjectMember, MemberVisibility}
import models.domain.genomics.{Biosample, CitizenBiosample, SpecimenDonor}
import org.mockito.ArgumentMatchers.{any, eq as meq}
import org.mockito.Mockito.{never, reset, verify, when}
import repositories.*

import java.time.{Instant, LocalDateTime}
import java.util.UUID
import scala.concurrent.Future

class ProjectStrComparisonServiceSpec extends ServiceSpec {

  val mockProjectRepo: GroupProjectRepository = mock[GroupProjectRepository]
  val mockMemberRepo: GroupProjectMemberRepository = mock[GroupProjectMemberRepository]
  val mockBiosampleRepo: CitizenBiosampleRepository = mock[CitizenBiosampleRepository]
  val mockBiosampleMainRepo: BiosampleRepository = mock[BiosampleRepository]
  val mockVariantCallRepo: BiosampleVariantCallRepository = mock[BiosampleVariantCallRepository]
  val mockStrRateRepo: StrMutationRateRepository = mock[StrMutationRateRepository]

  val service = new ProjectStrComparisonService(
    mockProjectRepo, mockMemberRepo, mockBiosampleRepo,
    mockBiosampleMainRepo, mockVariantCallRepo, mockStrRateRepo
  )

  override def beforeEach(): Unit = {
    reset(mockProjectRepo, mockMemberRepo, mockBiosampleRepo,
      mockBiosampleMainRepo, mockVariantCallRepo, mockStrRateRepo)
  }

  val now: LocalDateTime = LocalDateTime.now()

  val testProject: GroupProject = GroupProject(
    id = Some(1),
    projectGuid = UUID.randomUUID(),
    projectName = "R-CTS4466 STR Project",
    projectType = "HAPLOGROUP",
    targetHaplogroup = Some("R-CTS4466"),
    targetLineage = Some("Y_DNA"),
    joinPolicy = "APPROVAL_REQUIRED",
    strPolicy = "DISTANCE_ONLY",
    ownerDid = "did:plc:admin1"
  )

  val modalProject: GroupProject = testProject.copy(id = Some(2), strPolicy = "MODAL_COMPARISON")
  val hiddenStrProject: GroupProject = testProject.copy(id = Some(3), strPolicy = "HIDDEN")
  val publicStrProject: GroupProject = testProject.copy(id = Some(4), strPolicy = "PUBLIC_RAW")

  val sampleGuid1: UUID = UUID.randomUUID()
  val sampleGuid2: UUID = UUID.randomUUID()
  val sampleGuid3: UUID = UUID.randomUUID()

  def makeMember(id: Int, did: String, biosampleAtUri: Option[String] = None,
                 visibility: MemberVisibility = MemberVisibility(strVisibility = "DISTANCE_CALCULATION_ONLY"),
                 projectId: Int = 1): GroupProjectMember =
    GroupProjectMember(
      id = Some(id), groupProjectId = projectId, citizenDid = did,
      biosampleAtUri = biosampleAtUri, role = "MEMBER", status = "ACTIVE",
      visibility = visibility, joinedAt = Some(now)
    )

  val activeMemberViewer: GroupProjectMember = makeMember(99, "did:plc:viewer1")

  def makeCitizenBiosample(guid: UUID, atUri: String): CitizenBiosample =
    CitizenBiosample(
      id = Some(1), atUri = Some(atUri), accession = None, alias = None,
      sourcePlatform = None, collectionDate = None, sex = None, geocoord = None,
      description = None, sampleGuid = guid
    )

  def makeBiosample(id: Int, guid: UUID): Biosample =
    Biosample(id = Some(id), sampleGuid = guid, sampleAccession = s"ACC-$id",
      description = "Test", alias = None, centerName = "TestLab", specimenDonorId = None)

  val strRates: Seq[StrMutationRate] = Seq(
    StrMutationRate(id = Some(1), markerName = "DYS393", mutationRate = BigDecimal("0.00076")),
    StrMutationRate(id = Some(2), markerName = "DYS390", mutationRate = BigDecimal("0.00310")),
    StrMutationRate(id = Some(3), markerName = "DYS19", mutationRate = BigDecimal("0.00230"))
  )

  // STR calls: variantId corresponds to marker (1=DYS393, 2=DYS390, 3=DYS19)
  def makeStrCalls(biosampleId: Int, values: Map[Int, Int]): Seq[BiosampleVariantCall] =
    values.map { case (variantId, value) =>
      BiosampleVariantCall(
        id = Some(variantId * 100 + biosampleId),
        biosampleId = biosampleId,
        variantId = variantId,
        observedState = value.toString
      )
    }.toSeq

  private def setupBiosampleChain(memberId: Int, guid: UUID, biosampleId: Int, atUri: String,
                                  strValues: Map[Int, Int]): Unit = {
    when(mockBiosampleRepo.findByAtUri(atUri))
      .thenReturn(Future.successful(Some(makeCitizenBiosample(guid, atUri))))
    when(mockBiosampleMainRepo.findByGuid(guid))
      .thenReturn(Future.successful(Some((makeBiosample(biosampleId, guid), None))))
    when(mockVariantCallRepo.findByBiosample(biosampleId))
      .thenReturn(Future.successful(makeStrCalls(biosampleId, strValues)))
    when(mockStrRateRepo.findAll()).thenReturn(Future.successful(strRates))
  }

  "ProjectStrComparisonService.getProjectModalHaplotype" should {

    "compute modal haplotype from member STR data" in {
      val m1 = makeMember(1, "did:plc:m1", Some("at://m1/biosample/1"))
      val m2 = makeMember(2, "did:plc:m2", Some("at://m2/biosample/2"))
      val m3 = makeMember(3, "did:plc:m3", Some("at://m3/biosample/3"))

      when(mockProjectRepo.findById(2)).thenReturn(Future.successful(Some(modalProject)))
      when(mockMemberRepo.findByProjectAndCitizen(2, "did:plc:viewer1"))
        .thenReturn(Future.successful(Some(activeMemberViewer.copy(groupProjectId = 2))))
      when(mockMemberRepo.findByProjectAndStatus(2, "ACTIVE"))
        .thenReturn(Future.successful(Seq(m1, m2, m3)))

      // m1: DYS393=13, DYS390=24, DYS19=14
      setupBiosampleChain(1, sampleGuid1, 101, "at://m1/biosample/1", Map(1 -> 13, 2 -> 24, 3 -> 14))
      // m2: DYS393=13, DYS390=25, DYS19=14
      setupBiosampleChain(2, sampleGuid2, 102, "at://m2/biosample/2", Map(1 -> 13, 2 -> 25, 3 -> 14))
      // m3: DYS393=13, DYS390=24, DYS19=15
      setupBiosampleChain(3, sampleGuid3, 103, "at://m3/biosample/3", Map(1 -> 13, 2 -> 24, 3 -> 15))

      val result = service.getProjectModalHaplotype(2, "did:plc:viewer1").futureValue
      result.isRight mustBe true

      val modal = result.toOption.get
      modal.sampleCount mustBe 3

      // DYS393 (variant 1): all have 13 → modal = 13
      // DYS390 (variant 2): 24, 25, 24 → modal = 24
      // DYS19 (variant 3): 14, 14, 15 → modal = 14
      val modalMap = modal.markerModals.map(m => m.markerName -> m.modalValue).toMap
      // marker names here are variantId.toString since we use variantId as key
      modalMap.values.toSet must contain allOf(13, 24, 14)
    }

    "return error for project not found" in {
      when(mockProjectRepo.findById(999)).thenReturn(Future.successful(None))
      val result = service.getProjectModalHaplotype(999, "did:plc:viewer1").futureValue
      result mustBe Left("Project not found")
    }

    "return error for hidden STR project" in {
      when(mockProjectRepo.findById(3)).thenReturn(Future.successful(Some(hiddenStrProject)))
      val result = service.getProjectModalHaplotype(3, "did:plc:viewer1").futureValue
      result mustBe Left("Project STR policy does not allow STR operations")
    }

    "return error for non-member viewer on non-public project" in {
      when(mockProjectRepo.findById(2)).thenReturn(Future.successful(Some(modalProject)))
      when(mockMemberRepo.findByProjectAndCitizen(2, "did:plc:outsider"))
        .thenReturn(Future.successful(None))

      val result = service.getProjectModalHaplotype(2, "did:plc:outsider").futureValue
      result mustBe Left("Only active project members can access STR data")
    }

    "allow public access to PUBLIC_RAW project" in {
      when(mockProjectRepo.findById(4)).thenReturn(Future.successful(Some(publicStrProject)))
      when(mockMemberRepo.findByProjectAndStatus(4, "ACTIVE"))
        .thenReturn(Future.successful(Seq.empty))

      val result = service.getProjectModalHaplotype(4, "did:plc:anyone").futureValue
      result.isRight mustBe true
      result.toOption.get.sampleCount mustBe 0
    }
  }

  "ProjectStrComparisonService.getMemberDistanceFromModal" should {

    "compute distance between member and project modal" in {
      val m1 = makeMember(1, "did:plc:m1", Some("at://m1/biosample/1"), projectId = 2)
      val m2 = makeMember(2, "did:plc:m2", Some("at://m2/biosample/2"), projectId = 2)

      when(mockProjectRepo.findById(2)).thenReturn(Future.successful(Some(modalProject)))
      when(mockMemberRepo.findByProjectAndCitizen(2, "did:plc:viewer1"))
        .thenReturn(Future.successful(Some(activeMemberViewer.copy(groupProjectId = 2))))
      when(mockMemberRepo.findById(1)).thenReturn(Future.successful(Some(m1)))
      when(mockMemberRepo.findByProjectAndStatus(2, "ACTIVE"))
        .thenReturn(Future.successful(Seq(m1, m2)))

      // Modal will be: 1->13, 2->24 (mode of {24,25}=24 since m1 counted twice in modal)
      setupBiosampleChain(1, sampleGuid1, 101, "at://m1/biosample/1", Map(1 -> 13, 2 -> 24))
      setupBiosampleChain(2, sampleGuid2, 102, "at://m2/biosample/2", Map(1 -> 13, 2 -> 25))

      val result = service.getMemberDistanceFromModal(2, 1, "did:plc:viewer1").futureValue
      result.isRight mustBe true

      val comparison = result.toOption.get
      comparison.memberId mustBe 1
      comparison.markerCount must be > 0
    }

    "return error for member not found" in {
      when(mockProjectRepo.findById(2)).thenReturn(Future.successful(Some(modalProject)))
      when(mockMemberRepo.findByProjectAndCitizen(2, "did:plc:viewer1"))
        .thenReturn(Future.successful(Some(activeMemberViewer.copy(groupProjectId = 2))))
      when(mockMemberRepo.findById(999)).thenReturn(Future.successful(None))

      val result = service.getMemberDistanceFromModal(2, 999, "did:plc:viewer1").futureValue
      result mustBe Left("Member not found")
    }

    "return error for member with STR visibility NONE" in {
      val hiddenMember = makeMember(5, "did:plc:m5", Some("at://m5/biosample/5"),
        visibility = MemberVisibility(strVisibility = "NONE"), projectId = 2)

      when(mockProjectRepo.findById(2)).thenReturn(Future.successful(Some(modalProject)))
      when(mockMemberRepo.findByProjectAndCitizen(2, "did:plc:viewer1"))
        .thenReturn(Future.successful(Some(activeMemberViewer.copy(groupProjectId = 2))))
      when(mockMemberRepo.findById(5)).thenReturn(Future.successful(Some(hiddenMember)))

      val result = service.getMemberDistanceFromModal(2, 5, "did:plc:viewer1").futureValue
      result mustBe Left("Member STR data is not shared")
    }
  }

  "ProjectStrComparisonService.getMemberPairDistance" should {

    "compute genetic distance between two members" in {
      val m1 = makeMember(1, "did:plc:m1", Some("at://m1/biosample/1"))
      val m2 = makeMember(2, "did:plc:m2", Some("at://m2/biosample/2"))

      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(testProject)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:viewer1"))
        .thenReturn(Future.successful(Some(activeMemberViewer)))
      when(mockMemberRepo.findById(1)).thenReturn(Future.successful(Some(m1)))
      when(mockMemberRepo.findById(2)).thenReturn(Future.successful(Some(m2)))

      // m1: variant1=13, variant2=24, variant3=14
      setupBiosampleChain(1, sampleGuid1, 101, "at://m1/biosample/1", Map(1 -> 13, 2 -> 24, 3 -> 14))
      // m2: variant1=13, variant2=25, variant3=16
      setupBiosampleChain(2, sampleGuid2, 102, "at://m2/biosample/2", Map(1 -> 13, 2 -> 25, 3 -> 16))

      val result = service.getMemberPairDistance(1, 1, 2, "did:plc:viewer1").futureValue
      result.isRight mustBe true

      val distance = result.toOption.get
      distance.memberId1 mustBe 1
      distance.memberId2 mustBe 2
      // |13-13| + |24-25| + |14-16| = 0 + 1 + 2 = 3
      distance.geneticDistance mustBe 3
      distance.markerCount mustBe 3
      distance.normalizedDistance mustBe 1.0 // 3/3
    }

    "return error when member 1 not found" in {
      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(testProject)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:viewer1"))
        .thenReturn(Future.successful(Some(activeMemberViewer)))
      when(mockMemberRepo.findById(1)).thenReturn(Future.successful(None))
      when(mockMemberRepo.findById(2)).thenReturn(Future.successful(Some(makeMember(2, "did:plc:m2"))))

      val result = service.getMemberPairDistance(1, 1, 2, "did:plc:viewer1").futureValue
      result mustBe Left("Member 1 not found")
    }

    "return error when member STR is not shared" in {
      val m1 = makeMember(1, "did:plc:m1", Some("at://m1/biosample/1"),
        visibility = MemberVisibility(strVisibility = "NONE"))
      val m2 = makeMember(2, "did:plc:m2", Some("at://m2/biosample/2"))

      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(testProject)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:viewer1"))
        .thenReturn(Future.successful(Some(activeMemberViewer)))
      when(mockMemberRepo.findById(1)).thenReturn(Future.successful(Some(m1)))
      when(mockMemberRepo.findById(2)).thenReturn(Future.successful(Some(m2)))

      val result = service.getMemberPairDistance(1, 1, 2, "did:plc:viewer1").futureValue
      result mustBe Left("Member 1 STR data is not shared")
    }

    "return error when member not in project" in {
      val m1 = makeMember(1, "did:plc:m1", Some("at://m1/biosample/1"), projectId = 99)
      val m2 = makeMember(2, "did:plc:m2", Some("at://m2/biosample/2"))

      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(testProject)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:viewer1"))
        .thenReturn(Future.successful(Some(activeMemberViewer)))
      when(mockMemberRepo.findById(1)).thenReturn(Future.successful(Some(m1)))
      when(mockMemberRepo.findById(2)).thenReturn(Future.successful(Some(m2)))

      val result = service.getMemberPairDistance(1, 1, 2, "did:plc:viewer1").futureValue
      result mustBe Left("Member 1 not in this project")
    }
  }

  "ProjectStrComparisonService.getDistanceMatrix" should {

    "compute pairwise distance matrix for project members" in {
      val m1 = makeMember(1, "did:plc:m1", Some("at://m1/biosample/1"))
      val m2 = makeMember(2, "did:plc:m2", Some("at://m2/biosample/2"))
      val m3 = makeMember(3, "did:plc:m3", Some("at://m3/biosample/3"))

      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(testProject)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:viewer1"))
        .thenReturn(Future.successful(Some(activeMemberViewer)))
      when(mockMemberRepo.findByProjectAndStatus(1, "ACTIVE"))
        .thenReturn(Future.successful(Seq(m1, m2, m3)))

      setupBiosampleChain(1, sampleGuid1, 101, "at://m1/biosample/1", Map(1 -> 13, 2 -> 24))
      setupBiosampleChain(2, sampleGuid2, 102, "at://m2/biosample/2", Map(1 -> 14, 2 -> 24))
      setupBiosampleChain(3, sampleGuid3, 103, "at://m3/biosample/3", Map(1 -> 13, 2 -> 26))

      val result = service.getDistanceMatrix(1, "did:plc:viewer1").futureValue
      result.isRight mustBe true

      val matrix = result.toOption.get
      // 3 members → 3 pairs: (1,2), (1,3), (2,3)
      matrix.size mustBe 3

      val pair12 = matrix.find(d => d.memberId1 == 1 && d.memberId2 == 2).get
      pair12.geneticDistance mustBe 1 // |13-14| + |24-24| = 1

      val pair13 = matrix.find(d => d.memberId1 == 1 && d.memberId2 == 3).get
      pair13.geneticDistance mustBe 2 // |13-13| + |24-26| = 2

      val pair23 = matrix.find(d => d.memberId1 == 2 && d.memberId2 == 3).get
      pair23.geneticDistance mustBe 3 // |14-13| + |24-26| = 3
    }

    "exclude members with STR visibility NONE" in {
      val m1 = makeMember(1, "did:plc:m1", Some("at://m1/biosample/1"))
      val hiddenMember = makeMember(2, "did:plc:m2", Some("at://m2/biosample/2"),
        visibility = MemberVisibility(strVisibility = "NONE"))
      val m3 = makeMember(3, "did:plc:m3", Some("at://m3/biosample/3"))

      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(testProject)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:viewer1"))
        .thenReturn(Future.successful(Some(activeMemberViewer)))
      when(mockMemberRepo.findByProjectAndStatus(1, "ACTIVE"))
        .thenReturn(Future.successful(Seq(m1, hiddenMember, m3)))

      setupBiosampleChain(1, sampleGuid1, 101, "at://m1/biosample/1", Map(1 -> 13))
      setupBiosampleChain(3, sampleGuid3, 103, "at://m3/biosample/3", Map(1 -> 14))

      val result = service.getDistanceMatrix(1, "did:plc:viewer1").futureValue
      result.isRight mustBe true

      val matrix = result.toOption.get
      matrix.size mustBe 1 // Only pair (1,3), member 2 excluded
      matrix.head.memberId1 mustBe 1
      matrix.head.memberId2 mustBe 3
    }

    "return error for hidden STR project" in {
      when(mockProjectRepo.findById(3)).thenReturn(Future.successful(Some(hiddenStrProject)))
      val result = service.getDistanceMatrix(3, "did:plc:viewer1").futureValue
      result mustBe Left("Project STR policy does not allow STR operations")
    }
  }

  "ProjectStrComparisonService.resolveViewerPermission" should {

    "block all operations on HIDDEN STR policy" in {
      when(mockProjectRepo.findById(3)).thenReturn(Future.successful(Some(hiddenStrProject)))
      val result = service.getDistanceMatrix(3, "did:plc:viewer1").futureValue
      result mustBe Left("Project STR policy does not allow STR operations")
    }

    "require MODAL_COMPARISON level for modal operations on DISTANCE_ONLY project" in {
      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(testProject)))
      // testProject has strPolicy = "DISTANCE_ONLY" which maps to "DISTANCE_CALCULATION_ONLY"
      // getProjectModalHaplotype requires "MODAL_COMPARISON_ONLY" (rank 2)
      // "DISTANCE_CALCULATION_ONLY" has rank 1 < 2
      val result = service.getProjectModalHaplotype(1, "did:plc:viewer1").futureValue
      result mustBe Left("Project STR policy does not allow this operation")
    }
  }
}
