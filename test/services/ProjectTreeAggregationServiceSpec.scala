package services

import helpers.ServiceSpec
import models.HaplogroupType
import models.domain.{EffectiveVisibility, GroupProject, GroupProjectMember, MemberVisibility}
import models.domain.genomics.{BiosampleHaplogroup, CitizenBiosample}
import models.domain.haplogroups.Haplogroup
import org.mockito.ArgumentMatchers.{any, eq as meq}
import org.mockito.Mockito.{never, reset, verify, when}
import repositories.*

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.Future

class ProjectTreeAggregationServiceSpec extends ServiceSpec {

  val mockProjectRepo: GroupProjectRepository = mock[GroupProjectRepository]
  val mockMemberRepo: GroupProjectMemberRepository = mock[GroupProjectMemberRepository]
  val mockBiosampleRepo: CitizenBiosampleRepository = mock[CitizenBiosampleRepository]
  val mockBiosampleHgRepo: BiosampleHaplogroupRepository = mock[BiosampleHaplogroupRepository]
  val mockHaplogroupRepo: HaplogroupCoreRepository = mock[HaplogroupCoreRepository]

  val service = new ProjectTreeAggregationService(
    mockProjectRepo, mockMemberRepo, mockBiosampleRepo, mockBiosampleHgRepo, mockHaplogroupRepo
  )

  override def beforeEach(): Unit = {
    reset(mockProjectRepo, mockMemberRepo, mockBiosampleRepo, mockBiosampleHgRepo, mockHaplogroupRepo)
  }

  val now: LocalDateTime = LocalDateTime.now()

  val testProject: GroupProject = GroupProject(
    id = Some(1),
    projectGuid = UUID.randomUUID(),
    projectName = "R-CTS4466 Project",
    projectType = "HAPLOGROUP",
    targetHaplogroup = Some("R-CTS4466"),
    targetLineage = Some("Y_DNA"),
    joinPolicy = "APPROVAL_REQUIRED",
    publicTreeView = true,
    ownerDid = "did:plc:admin1"
  )

  val privateProject: GroupProject = testProject.copy(
    id = Some(2), publicTreeView = false, memberListVisibility = "MEMBERS_ONLY"
  )

  def makeMember(id: Int, did: String, biosampleAtUri: Option[String] = None,
                 visibility: MemberVisibility = MemberVisibility()): GroupProjectMember =
    GroupProjectMember(
      id = Some(id), groupProjectId = 1, citizenDid = did,
      biosampleAtUri = biosampleAtUri, role = "MEMBER", status = "ACTIVE",
      visibility = visibility, joinedAt = Some(now)
    )

  def makeHaplogroup(id: Int, name: String, formedYbp: Option[Int] = None,
                     tmrcaYbp: Option[Int] = None): Haplogroup =
    Haplogroup(
      id = Some(id), name = name, lineage = Some("Y-DNA"), description = None,
      haplogroupType = HaplogroupType.Y, revisionId = 1, source = "ISOGG",
      confidenceLevel = "HIGH", validFrom = now, validUntil = None,
      formedYbp = formedYbp, tmrcaYbp = tmrcaYbp
    )

  val sampleGuid1: UUID = UUID.randomUUID()
  val sampleGuid2: UUID = UUID.randomUUID()
  val sampleGuid3: UUID = UUID.randomUUID()

  def makeBiosample(guid: UUID, atUri: String): CitizenBiosample =
    CitizenBiosample(
      id = Some(1), atUri = Some(atUri), accession = None, alias = None,
      sourcePlatform = None, collectionDate = None, sex = None, geocoord = None,
      description = None, sampleGuid = guid
    )

  // Haplogroup tree structure:
  //   R (id=100)
  //   ├── R-M269 (id=101)
  //   │   ├── R-L151 (id=102)
  //   │   │   └── R-CTS4466 (id=103)
  //   │   └── R-Z2103 (id=104)
  //   └── R-M198 (id=105)

  val hapR: Haplogroup = makeHaplogroup(100, "R", formedYbp = Some(28000))
  val hapRM269: Haplogroup = makeHaplogroup(101, "R-M269", formedYbp = Some(13000))
  val hapRL151: Haplogroup = makeHaplogroup(102, "R-L151", formedYbp = Some(4800))
  val hapRCTS4466: Haplogroup = makeHaplogroup(103, "R-CTS4466", formedYbp = Some(3200))
  val hapRZ2103: Haplogroup = makeHaplogroup(104, "R-Z2103", formedYbp = Some(4600))
  val hapRM198: Haplogroup = makeHaplogroup(105, "R-M198", formedYbp = Some(10000))

  val allRelationships: Seq[(Int, Int)] = Seq(
    (101, 100), // R-M269 -> R
    (102, 101), // R-L151 -> R-M269
    (103, 102), // R-CTS4466 -> R-L151
    (104, 101), // R-Z2103 -> R-M269
    (105, 100)  // R-M198 -> R
  )

  val haplogroupLookup: Map[Int, Haplogroup] = Map(
    100 -> hapR, 101 -> hapRM269, 102 -> hapRL151,
    103 -> hapRCTS4466, 104 -> hapRZ2103, 105 -> hapRM198
  )

  private def setupHaplogroupLookups(): Unit = {
    haplogroupLookup.foreach { case (id, hg) =>
      when(mockHaplogroupRepo.findById(id)).thenReturn(Future.successful(Some(hg)))
    }
    when(mockHaplogroupRepo.getAllRelationships(HaplogroupType.Y)).thenReturn(Future.successful(allRelationships))

    // Ancestor paths
    when(mockHaplogroupRepo.getAncestors(100)).thenReturn(Future.successful(Seq.empty))
    when(mockHaplogroupRepo.getAncestors(101)).thenReturn(Future.successful(Seq(hapR)))
    when(mockHaplogroupRepo.getAncestors(102)).thenReturn(Future.successful(Seq(hapR, hapRM269)))
    when(mockHaplogroupRepo.getAncestors(103)).thenReturn(Future.successful(Seq(hapR, hapRM269, hapRL151)))
    when(mockHaplogroupRepo.getAncestors(104)).thenReturn(Future.successful(Seq(hapR, hapRM269)))
    when(mockHaplogroupRepo.getAncestors(105)).thenReturn(Future.successful(Seq(hapR)))
  }

  "ProjectTreeAggregationService.getAggregatedTree" should {

    "return aggregated tree with member counts" in {
      val member1 = makeMember(1, "did:plc:m1", Some("at://did:plc:m1/biosample/1"))
      val member2 = makeMember(2, "did:plc:m2", Some("at://did:plc:m2/biosample/2"))
      val member3 = makeMember(3, "did:plc:m3", Some("at://did:plc:m3/biosample/3"))

      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(testProject)))
      when(mockMemberRepo.findByProjectAndStatus(1, "ACTIVE")).thenReturn(
        Future.successful(Seq(member1, member2, member3)))

      when(mockBiosampleRepo.findByAtUri("at://did:plc:m1/biosample/1"))
        .thenReturn(Future.successful(Some(makeBiosample(sampleGuid1, "at://did:plc:m1/biosample/1"))))
      when(mockBiosampleRepo.findByAtUri("at://did:plc:m2/biosample/2"))
        .thenReturn(Future.successful(Some(makeBiosample(sampleGuid2, "at://did:plc:m2/biosample/2"))))
      when(mockBiosampleRepo.findByAtUri("at://did:plc:m3/biosample/3"))
        .thenReturn(Future.successful(Some(makeBiosample(sampleGuid3, "at://did:plc:m3/biosample/3"))))

      // m1 -> R-CTS4466, m2 -> R-CTS4466, m3 -> R-Z2103
      when(mockBiosampleHgRepo.findBySampleGuid(sampleGuid1))
        .thenReturn(Future.successful(Some(BiosampleHaplogroup(sampleGuid1, Some(103), None))))
      when(mockBiosampleHgRepo.findBySampleGuid(sampleGuid2))
        .thenReturn(Future.successful(Some(BiosampleHaplogroup(sampleGuid2, Some(103), None))))
      when(mockBiosampleHgRepo.findBySampleGuid(sampleGuid3))
        .thenReturn(Future.successful(Some(BiosampleHaplogroup(sampleGuid3, Some(104), None))))

      setupHaplogroupLookups()

      val result = service.getAggregatedTree(1, "Y_DNA", "did:plc:viewer1").futureValue
      result.isRight mustBe true

      val summary = result.toOption.get
      summary.projectId mustBe 1
      summary.lineageType mustBe "Y_DNA"
      summary.totalMembers mustBe 3
      summary.membersWithHaplogroup mustBe 3

      // Root should be R
      summary.rootNodes.size mustBe 1
      val root = summary.rootNodes.head
      root.haplogroupName mustBe "R"
      root.memberCount mustBe 0
      root.cumulativeCount mustBe 3

      // R should have R-M269 child (no R-M198 since no members there)
      val rm269 = root.children.find(_.haplogroupName == "R-M269")
      rm269 mustBe defined
      rm269.get.memberCount mustBe 0
      rm269.get.cumulativeCount mustBe 3

      // R-L151 should have 2 cumulative (2 at CTS4466)
      val rl151 = rm269.get.children.find(_.haplogroupName == "R-L151")
      rl151 mustBe defined
      rl151.get.cumulativeCount mustBe 2

      // R-CTS4466 should have 2 direct members
      val rcts = rl151.get.children.find(_.haplogroupName == "R-CTS4466")
      rcts mustBe defined
      rcts.get.memberCount mustBe 2
      rcts.get.cumulativeCount mustBe 2

      // R-Z2103 should have 1 direct member
      val rz2103 = rm269.get.children.find(_.haplogroupName == "R-Z2103")
      rz2103 mustBe defined
      rz2103.get.memberCount mustBe 1
      rz2103.get.cumulativeCount mustBe 1
    }

    "return error for invalid lineage type" in {
      val result = service.getAggregatedTree(1, "INVALID", "did:plc:viewer1").futureValue
      result mustBe Left("Invalid lineage type: INVALID")
    }

    "return error for non-existent project" in {
      when(mockProjectRepo.findById(999)).thenReturn(Future.successful(None))

      val result = service.getAggregatedTree(999, "Y_DNA", "did:plc:viewer1").futureValue
      result mustBe Left("Project not found")
    }

    "deny access to private project tree for non-members" in {
      when(mockProjectRepo.findById(2)).thenReturn(Future.successful(Some(privateProject)))
      when(mockMemberRepo.findByProjectAndCitizen(2, "did:plc:outsider"))
        .thenReturn(Future.successful(None))

      val result = service.getAggregatedTree(2, "Y_DNA", "did:plc:outsider").futureValue
      result mustBe Left("Insufficient permissions to view project tree")
    }

    "allow active members to view private project tree" in {
      val member = makeMember(1, "did:plc:m1", Some("at://did:plc:m1/biosample/1"))

      when(mockProjectRepo.findById(2)).thenReturn(Future.successful(Some(privateProject)))
      when(mockMemberRepo.findByProjectAndCitizen(2, "did:plc:m1"))
        .thenReturn(Future.successful(Some(member)))
      when(mockMemberRepo.findByProjectAndStatus(2, "ACTIVE"))
        .thenReturn(Future.successful(Seq(member)))
      when(mockBiosampleRepo.findByAtUri("at://did:plc:m1/biosample/1"))
        .thenReturn(Future.successful(Some(makeBiosample(sampleGuid1, "at://did:plc:m1/biosample/1"))))
      when(mockBiosampleHgRepo.findBySampleGuid(sampleGuid1))
        .thenReturn(Future.successful(Some(BiosampleHaplogroup(sampleGuid1, Some(103), None))))
      setupHaplogroupLookups()

      val result = service.getAggregatedTree(2, "Y_DNA", "did:plc:m1").futureValue
      result.isRight mustBe true
    }

    "exclude members with showInTree=false" in {
      val visibleMember = makeMember(1, "did:plc:m1", Some("at://did:plc:m1/biosample/1"))
      val hiddenMember = makeMember(2, "did:plc:m2", Some("at://did:plc:m2/biosample/2"),
        visibility = MemberVisibility(showInTree = false))

      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(testProject)))
      when(mockMemberRepo.findByProjectAndStatus(1, "ACTIVE"))
        .thenReturn(Future.successful(Seq(visibleMember, hiddenMember)))

      when(mockBiosampleRepo.findByAtUri("at://did:plc:m1/biosample/1"))
        .thenReturn(Future.successful(Some(makeBiosample(sampleGuid1, "at://did:plc:m1/biosample/1"))))
      when(mockBiosampleHgRepo.findBySampleGuid(sampleGuid1))
        .thenReturn(Future.successful(Some(BiosampleHaplogroup(sampleGuid1, Some(103), None))))
      setupHaplogroupLookups()

      val result = service.getAggregatedTree(1, "Y_DNA", "did:plc:viewer1").futureValue
      result.isRight mustBe true
      val summary = result.toOption.get
      summary.totalMembers mustBe 1
      summary.membersWithHaplogroup mustBe 1
    }

    "handle members without biosample links" in {
      val memberWithBiosample = makeMember(1, "did:plc:m1", Some("at://did:plc:m1/biosample/1"))
      val memberWithout = makeMember(2, "did:plc:m2", None)

      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(testProject)))
      when(mockMemberRepo.findByProjectAndStatus(1, "ACTIVE"))
        .thenReturn(Future.successful(Seq(memberWithBiosample, memberWithout)))

      when(mockBiosampleRepo.findByAtUri("at://did:plc:m1/biosample/1"))
        .thenReturn(Future.successful(Some(makeBiosample(sampleGuid1, "at://did:plc:m1/biosample/1"))))
      when(mockBiosampleHgRepo.findBySampleGuid(sampleGuid1))
        .thenReturn(Future.successful(Some(BiosampleHaplogroup(sampleGuid1, Some(103), None))))
      setupHaplogroupLookups()

      val result = service.getAggregatedTree(1, "Y_DNA", "did:plc:viewer1").futureValue
      result.isRight mustBe true
      val summary = result.toOption.get
      summary.totalMembers mustBe 2
      summary.membersWithHaplogroup mustBe 1
    }

    "handle members with biosample but no haplogroup assignment" in {
      val member = makeMember(1, "did:plc:m1", Some("at://did:plc:m1/biosample/1"))

      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(testProject)))
      when(mockMemberRepo.findByProjectAndStatus(1, "ACTIVE"))
        .thenReturn(Future.successful(Seq(member)))

      when(mockBiosampleRepo.findByAtUri("at://did:plc:m1/biosample/1"))
        .thenReturn(Future.successful(Some(makeBiosample(sampleGuid1, "at://did:plc:m1/biosample/1"))))
      when(mockBiosampleHgRepo.findBySampleGuid(sampleGuid1))
        .thenReturn(Future.successful(None))

      val result = service.getAggregatedTree(1, "Y_DNA", "did:plc:viewer1").futureValue
      result.isRight mustBe true
      val summary = result.toOption.get
      summary.totalMembers mustBe 1
      summary.membersWithHaplogroup mustBe 0
      summary.rootNodes mustBe empty
    }

    "return empty tree for project with no members" in {
      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(testProject)))
      when(mockMemberRepo.findByProjectAndStatus(1, "ACTIVE"))
        .thenReturn(Future.successful(Seq.empty))

      val result = service.getAggregatedTree(1, "Y_DNA", "did:plc:viewer1").futureValue
      result.isRight mustBe true
      val summary = result.toOption.get
      summary.totalMembers mustBe 0
      summary.membersWithHaplogroup mustBe 0
      summary.rootNodes mustBe empty
    }

    "include age estimates in tree nodes" in {
      val member = makeMember(1, "did:plc:m1", Some("at://did:plc:m1/biosample/1"))

      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(testProject)))
      when(mockMemberRepo.findByProjectAndStatus(1, "ACTIVE"))
        .thenReturn(Future.successful(Seq(member)))

      when(mockBiosampleRepo.findByAtUri("at://did:plc:m1/biosample/1"))
        .thenReturn(Future.successful(Some(makeBiosample(sampleGuid1, "at://did:plc:m1/biosample/1"))))
      when(mockBiosampleHgRepo.findBySampleGuid(sampleGuid1))
        .thenReturn(Future.successful(Some(BiosampleHaplogroup(sampleGuid1, Some(103), None))))
      setupHaplogroupLookups()

      val result = service.getAggregatedTree(1, "Y_DNA", "did:plc:viewer1").futureValue
      val summary = result.toOption.get
      val root = summary.rootNodes.head
      root.formedYbp mustBe Some(28000)

      // Navigate to R-CTS4466
      val cts4466 = root.children.head.children.head.children.head
      cts4466.formedYbp mustBe Some(3200)
    }

    "support MT_DNA lineage type" in {
      val member = makeMember(1, "did:plc:m1", Some("at://did:plc:m1/biosample/1"))
      val mtHaplogroup = Haplogroup(
        id = Some(200), name = "H", lineage = Some("MT-DNA"), description = None,
        haplogroupType = HaplogroupType.MT, revisionId = 1, source = "PhyloTree",
        confidenceLevel = "HIGH", validFrom = now, validUntil = None
      )

      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(testProject)))
      when(mockMemberRepo.findByProjectAndStatus(1, "ACTIVE"))
        .thenReturn(Future.successful(Seq(member)))

      when(mockBiosampleRepo.findByAtUri("at://did:plc:m1/biosample/1"))
        .thenReturn(Future.successful(Some(makeBiosample(sampleGuid1, "at://did:plc:m1/biosample/1"))))
      when(mockBiosampleHgRepo.findBySampleGuid(sampleGuid1))
        .thenReturn(Future.successful(Some(BiosampleHaplogroup(sampleGuid1, None, Some(200)))))

      when(mockHaplogroupRepo.findById(200)).thenReturn(Future.successful(Some(mtHaplogroup)))
      when(mockHaplogroupRepo.getAncestors(200)).thenReturn(Future.successful(Seq.empty))
      when(mockHaplogroupRepo.getAllRelationships(HaplogroupType.MT)).thenReturn(Future.successful(Seq.empty))

      val result = service.getAggregatedTree(1, "MT_DNA", "did:plc:viewer1").futureValue
      result.isRight mustBe true
      val summary = result.toOption.get
      summary.lineageType mustBe "MT_DNA"
      summary.rootNodes.size mustBe 1
      summary.rootNodes.head.haplogroupName mustBe "H"
    }
  }

  "ProjectTreeAggregationService.getBranchMemberCount" should {

    "count members at a specific branch including descendants" in {
      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(testProject)))
      when(mockMemberRepo.findByProjectAndStatus(1, "ACTIVE")).thenReturn(
        Future.successful(Seq(
          makeMember(1, "did:plc:m1", Some("at://did:plc:m1/biosample/1")),
          makeMember(2, "did:plc:m2", Some("at://did:plc:m2/biosample/2")),
          makeMember(3, "did:plc:m3", Some("at://did:plc:m3/biosample/3"))
        )))

      when(mockBiosampleRepo.findByAtUri("at://did:plc:m1/biosample/1"))
        .thenReturn(Future.successful(Some(makeBiosample(sampleGuid1, "at://did:plc:m1/biosample/1"))))
      when(mockBiosampleRepo.findByAtUri("at://did:plc:m2/biosample/2"))
        .thenReturn(Future.successful(Some(makeBiosample(sampleGuid2, "at://did:plc:m2/biosample/2"))))
      when(mockBiosampleRepo.findByAtUri("at://did:plc:m3/biosample/3"))
        .thenReturn(Future.successful(Some(makeBiosample(sampleGuid3, "at://did:plc:m3/biosample/3"))))

      // m1 -> R-CTS4466 (103), m2 -> R-L151 (102), m3 -> R-Z2103 (104)
      when(mockBiosampleHgRepo.findBySampleGuid(sampleGuid1))
        .thenReturn(Future.successful(Some(BiosampleHaplogroup(sampleGuid1, Some(103), None))))
      when(mockBiosampleHgRepo.findBySampleGuid(sampleGuid2))
        .thenReturn(Future.successful(Some(BiosampleHaplogroup(sampleGuid2, Some(102), None))))
      when(mockBiosampleHgRepo.findBySampleGuid(sampleGuid3))
        .thenReturn(Future.successful(Some(BiosampleHaplogroup(sampleGuid3, Some(104), None))))

      // R-M269 (101) descendants: R-L151, R-CTS4466, R-Z2103
      when(mockHaplogroupRepo.getDescendants(101)).thenReturn(
        Future.successful(Seq(hapRL151, hapRCTS4466, hapRZ2103)))

      val result = service.getBranchMemberCount(1, 101, "Y_DNA").futureValue
      result mustBe Right(3) // All 3 members are under R-M269
    }

    "count only direct members at a leaf branch" in {
      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(testProject)))
      when(mockMemberRepo.findByProjectAndStatus(1, "ACTIVE")).thenReturn(
        Future.successful(Seq(
          makeMember(1, "did:plc:m1", Some("at://did:plc:m1/biosample/1")),
          makeMember(2, "did:plc:m2", Some("at://did:plc:m2/biosample/2"))
        )))

      when(mockBiosampleRepo.findByAtUri("at://did:plc:m1/biosample/1"))
        .thenReturn(Future.successful(Some(makeBiosample(sampleGuid1, "at://did:plc:m1/biosample/1"))))
      when(mockBiosampleRepo.findByAtUri("at://did:plc:m2/biosample/2"))
        .thenReturn(Future.successful(Some(makeBiosample(sampleGuid2, "at://did:plc:m2/biosample/2"))))

      // m1 -> R-CTS4466, m2 -> R-Z2103
      when(mockBiosampleHgRepo.findBySampleGuid(sampleGuid1))
        .thenReturn(Future.successful(Some(BiosampleHaplogroup(sampleGuid1, Some(103), None))))
      when(mockBiosampleHgRepo.findBySampleGuid(sampleGuid2))
        .thenReturn(Future.successful(Some(BiosampleHaplogroup(sampleGuid2, Some(104), None))))

      // R-CTS4466 (103) has no descendants
      when(mockHaplogroupRepo.getDescendants(103)).thenReturn(Future.successful(Seq.empty))

      val result = service.getBranchMemberCount(1, 103, "Y_DNA").futureValue
      result mustBe Right(1) // Only m1 at CTS4466
    }

    "return error for non-existent project" in {
      when(mockProjectRepo.findById(999)).thenReturn(Future.successful(None))

      val result = service.getBranchMemberCount(999, 101, "Y_DNA").futureValue
      result mustBe Left("Project not found")
    }

    "return error for invalid lineage type" in {
      val result = service.getBranchMemberCount(1, 101, "AUTOSOMAL").futureValue
      result mustBe Left("Invalid lineage type: AUTOSOMAL")
    }
  }

  "ProjectTreeAggregationService.buildTreeFromCounts" should {

    "build tree from a single haplogroup" in {
      when(mockHaplogroupRepo.findById(103)).thenReturn(Future.successful(Some(hapRCTS4466)))
      when(mockHaplogroupRepo.getAncestors(103)).thenReturn(
        Future.successful(Seq(hapR, hapRM269, hapRL151)))
      when(mockHaplogroupRepo.getAllRelationships(HaplogroupType.Y)).thenReturn(
        Future.successful(allRelationships))

      // Also set up findById for ancestor nodes
      when(mockHaplogroupRepo.findById(100)).thenReturn(Future.successful(Some(hapR)))
      when(mockHaplogroupRepo.findById(101)).thenReturn(Future.successful(Some(hapRM269)))
      when(mockHaplogroupRepo.findById(102)).thenReturn(Future.successful(Some(hapRL151)))

      val counts = Map(103 -> 5)
      val result = service.buildTreeFromCounts(counts, HaplogroupType.Y).futureValue

      result.size mustBe 1
      val root = result.head
      root.haplogroupName mustBe "R"
      root.memberCount mustBe 0
      root.cumulativeCount mustBe 5

      val rm269 = root.children.head
      rm269.haplogroupName mustBe "R-M269"
      rm269.cumulativeCount mustBe 5

      val rl151 = rm269.children.head
      rl151.haplogroupName mustBe "R-L151"
      rl151.cumulativeCount mustBe 5

      val cts4466 = rl151.children.head
      cts4466.haplogroupName mustBe "R-CTS4466"
      cts4466.memberCount mustBe 5
      cts4466.cumulativeCount mustBe 5
    }

    "build tree with multiple branches" in {
      setupHaplogroupLookups()

      val counts = Map(103 -> 3, 104 -> 2, 105 -> 1)
      val result = service.buildTreeFromCounts(counts, HaplogroupType.Y).futureValue

      result.size mustBe 1
      val root = result.head
      root.haplogroupName mustBe "R"
      root.cumulativeCount mustBe 6

      root.children.map(_.haplogroupName).toSet mustBe Set("R-M269", "R-M198")

      val rm198 = root.children.find(_.haplogroupName == "R-M198").get
      rm198.memberCount mustBe 1
      rm198.cumulativeCount mustBe 1
    }

    "return empty for empty counts" in {
      val result = service.buildTreeFromCounts(Map.empty, HaplogroupType.Y).futureValue
      result mustBe empty
    }
  }

  "ProjectTreeAggregationService.resolveHaplogroupAssignments" should {

    "resolve Y haplogroup from biosample chain" in {
      val member = makeMember(1, "did:plc:m1", Some("at://did:plc:m1/biosample/1"))

      when(mockBiosampleRepo.findByAtUri("at://did:plc:m1/biosample/1"))
        .thenReturn(Future.successful(Some(makeBiosample(sampleGuid1, "at://did:plc:m1/biosample/1"))))
      when(mockBiosampleHgRepo.findBySampleGuid(sampleGuid1))
        .thenReturn(Future.successful(Some(BiosampleHaplogroup(sampleGuid1, Some(103), Some(200)))))

      val result = service.resolveHaplogroupAssignments(testProject, HaplogroupType.Y, Seq(member)).futureValue
      result mustBe Seq(103)
    }

    "resolve MT haplogroup from biosample chain" in {
      val member = makeMember(1, "did:plc:m1", Some("at://did:plc:m1/biosample/1"))

      when(mockBiosampleRepo.findByAtUri("at://did:plc:m1/biosample/1"))
        .thenReturn(Future.successful(Some(makeBiosample(sampleGuid1, "at://did:plc:m1/biosample/1"))))
      when(mockBiosampleHgRepo.findBySampleGuid(sampleGuid1))
        .thenReturn(Future.successful(Some(BiosampleHaplogroup(sampleGuid1, Some(103), Some(200)))))

      val result = service.resolveHaplogroupAssignments(testProject, HaplogroupType.MT, Seq(member)).futureValue
      result mustBe Seq(200)
    }

    "skip members with unresolvable biosamples" in {
      val member = makeMember(1, "did:plc:m1", Some("at://did:plc:m1/biosample/missing"))

      when(mockBiosampleRepo.findByAtUri("at://did:plc:m1/biosample/missing"))
        .thenReturn(Future.successful(None))

      val result = service.resolveHaplogroupAssignments(testProject, HaplogroupType.Y, Seq(member)).futureValue
      result mustBe empty
    }
  }
}
