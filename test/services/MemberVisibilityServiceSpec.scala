package services

import helpers.ServiceSpec
import models.domain.*
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{reset, when}
import repositories.{GroupProjectMemberRepository, GroupProjectRepository}

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.Future

class MemberVisibilityServiceSpec extends ServiceSpec {

  val mockProjectRepo: GroupProjectRepository = mock[GroupProjectRepository]
  val mockMemberRepo: GroupProjectMemberRepository = mock[GroupProjectMemberRepository]

  val service = new MemberVisibilityService(mockProjectRepo, mockMemberRepo)

  override def beforeEach(): Unit = {
    reset(mockProjectRepo, mockMemberRepo)
  }

  val project: GroupProject = GroupProject(
    id = Some(1), projectName = "Test Project", projectType = "HAPLOGROUP",
    joinPolicy = "APPROVAL_REQUIRED", ownerDid = "did:plc:admin1",
    memberListVisibility = "MEMBERS_ONLY", strPolicy = "DISTANCE_ONLY",
    snpPolicy = "TERMINAL_ONLY", publicTreeView = false
  )

  val fullVisProject: GroupProject = project.copy(
    snpPolicy = "WITH_PRIVATE_VARIANTS", strPolicy = "MEMBERS_ONLY_RAW",
    memberListVisibility = "PUBLIC", publicTreeView = true
  )

  val hiddenSnpProject: GroupProject = project.copy(snpPolicy = "HIDDEN", strPolicy = "HIDDEN")

  val activeMember: GroupProjectMember = GroupProjectMember(
    id = Some(10), groupProjectId = 1, citizenDid = "did:plc:member1",
    role = "MEMBER", status = "ACTIVE",
    displayName = Some("John"),
    kitId = Some("KIT-123"),
    visibility = MemberVisibility(
      showInMemberList = true, showInTree = true,
      shareTerminalHaplogroup = true, shareFullLineagePath = true,
      sharePrivateVariants = true, ancestorVisibility = "FULL",
      strVisibility = "FULL_TO_MEMBERS", allowDirectContact = true,
      showDisplayName = true
    ),
    joinedAt = Some(LocalDateTime.now())
  )

  val restrictiveMember: GroupProjectMember = activeMember.copy(
    id = Some(11), citizenDid = "did:plc:member2",
    visibility = MemberVisibility(
      showInMemberList = false, showInTree = false,
      shareTerminalHaplogroup = false, shareFullLineagePath = false,
      sharePrivateVariants = false, ancestorVisibility = "NONE",
      strVisibility = "NONE", allowDirectContact = false,
      showDisplayName = false
    )
  )

  val adminMember: GroupProjectMember = GroupProjectMember(
    id = Some(1), groupProjectId = 1, citizenDid = "did:plc:admin1",
    role = "ADMIN", status = "ACTIVE", joinedAt = Some(LocalDateTime.now())
  )

  val fullAncestor: AncestorData = AncestorData(
    name = Some("Johann Mueller"), surname = Some("Mueller"),
    birthYear = Some(1745), birthCentury = Some("18th century"),
    birthDecade = Some("1740s"), birthCountry = Some("Germany"),
    birthRegion = Some("Bavaria"), birthPlace = Some("Munich"),
    additionalInfo = Some("Farmer in Munich")
  )

  // --- EffectiveVisibility.compute tests ---

  "EffectiveVisibility.compute" should {

    "respect more restrictive of project and member (project restricts SNP)" in {
      val effective = EffectiveVisibility.compute(project, activeMember.visibility)
      effective.shareTerminalHaplogroup mustBe true // TERMINAL_ONLY allows terminal
      effective.shareFullLineagePath mustBe false   // TERMINAL_ONLY blocks full path
      effective.sharePrivateVariants mustBe false    // TERMINAL_ONLY blocks private
    }

    "allow everything when project is permissive and member opts in" in {
      val effective = EffectiveVisibility.compute(fullVisProject, activeMember.visibility)
      effective.shareTerminalHaplogroup mustBe true
      effective.shareFullLineagePath mustBe true
      effective.sharePrivateVariants mustBe true
      effective.showInMemberList mustBe true
    }

    "restrict everything when member opts out" in {
      val effective = EffectiveVisibility.compute(fullVisProject, restrictiveMember.visibility)
      effective.shareTerminalHaplogroup mustBe false
      effective.shareFullLineagePath mustBe false
      effective.sharePrivateVariants mustBe false
      effective.showInMemberList mustBe false
      effective.showInTree mustBe false
      effective.allowDirectContact mustBe false
      effective.showDisplayName mustBe false
    }

    "restrict everything when project hides SNP/STR" in {
      val effective = EffectiveVisibility.compute(hiddenSnpProject, activeMember.visibility)
      effective.shareTerminalHaplogroup mustBe false
      effective.strVisibility mustBe "NONE"
    }

    "use more restrictive STR level between project and member" in {
      // project allows DISTANCE_ONLY, member wants FULL_TO_MEMBERS → DISTANCE_CALCULATION_ONLY
      val effective = EffectiveVisibility.compute(project, activeMember.visibility)
      effective.strVisibility mustBe "DISTANCE_CALCULATION_ONLY"
    }

    "use more restrictive ancestor level" in {
      val memberWithRegion = activeMember.visibility.copy(ancestorVisibility = "REGION_ONLY")
      val effective = EffectiveVisibility.compute(project, memberWithRegion)
      effective.ancestorVisibility mustBe "REGION_ONLY"
    }
  }

  // --- AncestorData.filter tests ---

  "AncestorData.filter" should {

    "return empty for NONE" in {
      val filtered = AncestorData.filter(fullAncestor, "NONE")
      filtered.name mustBe None
      filtered.surname mustBe None
      filtered.birthCountry mustBe None
    }

    "return only century for CENTURY_ONLY" in {
      val filtered = AncestorData.filter(fullAncestor, "CENTURY_ONLY")
      filtered.birthCentury mustBe Some("18th century")
      filtered.name mustBe None
      filtered.surname mustBe None
      filtered.birthCountry mustBe None
    }

    "return country and region for REGION_ONLY" in {
      val filtered = AncestorData.filter(fullAncestor, "REGION_ONLY")
      filtered.birthCountry mustBe Some("Germany")
      filtered.birthRegion mustBe Some("Bavaria")
      filtered.name mustBe None
      filtered.birthCentury mustBe None
    }

    "return only country for COUNTRY_ONLY" in {
      val filtered = AncestorData.filter(fullAncestor, "COUNTRY_ONLY")
      filtered.birthCountry mustBe Some("Germany")
      filtered.birthRegion mustBe None
    }

    "return surname and century for SURNAME_ONLY" in {
      val filtered = AncestorData.filter(fullAncestor, "SURNAME_ONLY")
      filtered.surname mustBe Some("Mueller")
      filtered.birthCentury mustBe Some("18th century")
      filtered.name mustBe None
      filtered.birthCountry mustBe None
    }

    "return everything for FULL" in {
      val filtered = AncestorData.filter(fullAncestor, "FULL")
      filtered mustBe fullAncestor
    }
  }

  // --- MemberVisibilityService.updateVisibility tests ---

  "MemberVisibilityService.updateVisibility" should {

    "allow member to update own visibility" in {
      when(mockMemberRepo.findById(10)).thenReturn(Future.successful(Some(activeMember)))
      when(mockMemberRepo.update(any[GroupProjectMember])).thenReturn(Future.successful(true))

      val newVis = MemberVisibility(showInTree = false)
      whenReady(service.updateVisibility(10, "did:plc:member1", newVis)) { result =>
        result mustBe a[Right[?, ?]]
        result.toOption.get.visibility.showInTree mustBe false
      }
    }

    "reject update from different user" in {
      when(mockMemberRepo.findById(10)).thenReturn(Future.successful(Some(activeMember)))

      val newVis = MemberVisibility(showInTree = false)
      whenReady(service.updateVisibility(10, "did:plc:other", newVis)) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get must include("Only the member")
      }
    }

    "reject invalid ancestor visibility" in {
      when(mockMemberRepo.findById(10)).thenReturn(Future.successful(Some(activeMember)))

      val badVis = MemberVisibility(ancestorVisibility = "EVERYTHING")
      whenReady(service.updateVisibility(10, "did:plc:member1", badVis)) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get must include("Invalid ancestor visibility")
      }
    }

    "reject invalid STR visibility" in {
      when(mockMemberRepo.findById(10)).thenReturn(Future.successful(Some(activeMember)))

      val badVis = MemberVisibility(strVisibility = "RAW_PUBLIC")
      whenReady(service.updateVisibility(10, "did:plc:member1", badVis)) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get must include("Invalid STR visibility")
      }
    }

    "reject update for inactive membership" in {
      val suspended = activeMember.copy(status = "SUSPENDED")
      when(mockMemberRepo.findById(10)).thenReturn(Future.successful(Some(suspended)))

      whenReady(service.updateVisibility(10, "did:plc:member1", MemberVisibility())) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get must include("active")
      }
    }
  }

  // --- MemberVisibilityService.getEffectiveVisibility tests ---

  "MemberVisibilityService.getEffectiveVisibility" should {

    "return computed effective visibility" in {
      when(mockMemberRepo.findById(10)).thenReturn(Future.successful(Some(activeMember)))
      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(project)))

      whenReady(service.getEffectiveVisibility(10)) { result =>
        result mustBe defined
        val eff = result.get
        eff.shareTerminalHaplogroup mustBe true
        eff.shareFullLineagePath mustBe false // project is TERMINAL_ONLY
      }
    }

    "return None for unknown member" in {
      when(mockMemberRepo.findById(99)).thenReturn(Future.successful(None))

      whenReady(service.getEffectiveVisibility(99)) { _ mustBe None }
    }
  }

  // --- MemberVisibilityService.getFilteredMemberView tests ---

  "MemberVisibilityService.getFilteredMemberView" should {

    "filter haplogroup data based on effective visibility" in {
      when(mockMemberRepo.findById(10)).thenReturn(Future.successful(Some(activeMember)))
      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(project)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:viewer"))
        .thenReturn(Future.successful(Some(adminMember.copy(citizenDid = "did:plc:viewer", role = "MEMBER"))))

      whenReady(service.getFilteredMemberView(
        10, "did:plc:viewer",
        haplogroup = Some("R-CTS4466"),
        lineagePath = Some(Seq("R", "R1b", "R-M269", "R-CTS4466")),
        privateVariantCount = Some(5),
        ancestor = fullAncestor
      )) { result =>
        result mustBe defined
        val view = result.get
        view.terminalHaplogroup mustBe Some("R-CTS4466") // allowed by TERMINAL_ONLY
        view.lineagePath mustBe None                      // blocked by TERMINAL_ONLY
        view.privateVariantCount mustBe None              // blocked by TERMINAL_ONLY
        view.displayName mustBe Some("John")
        view.ancestor.name mustBe Some("Johann Mueller")  // member allows FULL
      }
    }

    "show all data when project and member both allow" in {
      when(mockMemberRepo.findById(10)).thenReturn(Future.successful(Some(activeMember)))
      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(fullVisProject)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:viewer"))
        .thenReturn(Future.successful(Some(adminMember.copy(citizenDid = "did:plc:viewer", role = "MEMBER"))))

      whenReady(service.getFilteredMemberView(
        10, "did:plc:viewer",
        haplogroup = Some("R-CTS4466"),
        lineagePath = Some(Seq("R", "R1b", "R-CTS4466")),
        privateVariantCount = Some(5)
      )) { result =>
        val view = result.get
        view.terminalHaplogroup mustBe Some("R-CTS4466")
        view.lineagePath mustBe Some(Seq("R", "R1b", "R-CTS4466"))
        view.privateVariantCount mustBe Some(5)
      }
    }

    "hide everything for restrictive member" in {
      when(mockMemberRepo.findById(11)).thenReturn(Future.successful(Some(restrictiveMember)))
      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(fullVisProject)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:viewer"))
        .thenReturn(Future.successful(Some(adminMember.copy(citizenDid = "did:plc:viewer", role = "MEMBER"))))

      whenReady(service.getFilteredMemberView(
        11, "did:plc:viewer",
        haplogroup = Some("R-CTS4466"),
        ancestor = fullAncestor
      )) { result =>
        val view = result.get
        view.terminalHaplogroup mustBe None
        view.displayName mustBe None
        view.ancestor.name mustBe None
        view.ancestor.birthCountry mustBe None
        view.allowDirectContact mustBe false
      }
    }

    "only allow direct contact from members" in {
      when(mockMemberRepo.findById(10)).thenReturn(Future.successful(Some(activeMember)))
      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(project)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:outsider"))
        .thenReturn(Future.successful(None))

      whenReady(service.getFilteredMemberView(10, "did:plc:outsider")) { result =>
        result.get.allowDirectContact mustBe false
      }
    }
  }

  // --- MemberVisibilityService.getFilteredMembersForProject tests ---

  "MemberVisibilityService.getFilteredMembersForProject" should {

    "filter out hidden members for non-admins" in {
      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(project)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:viewer"))
        .thenReturn(Future.successful(Some(adminMember.copy(citizenDid = "did:plc:viewer", role = "MEMBER"))))
      when(mockMemberRepo.findByProjectAndStatus(1, "ACTIVE"))
        .thenReturn(Future.successful(Seq(activeMember, restrictiveMember)))

      val data = Map(
        10 -> MemberSupplementalData(haplogroup = Some("R-CTS4466")),
        11 -> MemberSupplementalData(haplogroup = Some("R-L21"))
      )

      whenReady(service.getFilteredMembersForProject(1, "did:plc:viewer", data)) { result =>
        result mustBe a[Right[?, ?]]
        val views = result.toOption.get
        views.size mustBe 1 // restrictiveMember hidden from member list
        views.head.terminalHaplogroup mustBe Some("R-CTS4466")
      }
    }

    "show hidden members to admin" in {
      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(project)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:admin1"))
        .thenReturn(Future.successful(Some(adminMember)))
      when(mockMemberRepo.findByProjectAndStatus(1, "ACTIVE"))
        .thenReturn(Future.successful(Seq(activeMember, restrictiveMember)))

      whenReady(service.getFilteredMembersForProject(1, "did:plc:admin1", Map.empty)) { result =>
        result.toOption.get.size mustBe 2
      }
    }

    "deny non-member access to MEMBERS_ONLY list" in {
      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(project)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:outsider"))
        .thenReturn(Future.successful(None))
      when(mockMemberRepo.findByProjectAndStatus(1, "ACTIVE"))
        .thenReturn(Future.successful(Seq(activeMember)))

      whenReady(service.getFilteredMembersForProject(1, "did:plc:outsider", Map.empty)) { result =>
        result mustBe a[Left[?, ?]]
      }
    }
  }

  // --- MemberVisibility helpers ---

  "MemberVisibility.moreRestrictiveAncestor" should {

    "pick NONE over FULL" in {
      MemberVisibility.moreRestrictiveAncestor("NONE", "FULL") mustBe "NONE"
    }

    "pick CENTURY_ONLY over SURNAME_ONLY" in {
      MemberVisibility.moreRestrictiveAncestor("CENTURY_ONLY", "SURNAME_ONLY") mustBe "CENTURY_ONLY"
    }

    "pick same when equal" in {
      MemberVisibility.moreRestrictiveAncestor("REGION_ONLY", "REGION_ONLY") mustBe "REGION_ONLY"
    }
  }

  "MemberVisibility.moreRestrictiveStr" should {

    "pick NONE over FULL_PUBLIC" in {
      MemberVisibility.moreRestrictiveStr("NONE", "FULL_PUBLIC") mustBe "NONE"
    }

    "pick DISTANCE_CALCULATION_ONLY over MODAL_COMPARISON_ONLY" in {
      MemberVisibility.moreRestrictiveStr("DISTANCE_CALCULATION_ONLY", "MODAL_COMPARISON_ONLY") mustBe "DISTANCE_CALCULATION_ONLY"
    }
  }
}
