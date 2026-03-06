package services

import helpers.ServiceSpec
import models.domain.{GroupProject, GroupProjectMember, MemberVisibility}
import org.mockito.ArgumentMatchers.{any, eq as meq}
import org.mockito.Mockito.{never, reset, verify, when}
import repositories.{GroupProjectMemberRepository, GroupProjectRepository}

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.Future

class GroupProjectServiceSpec extends ServiceSpec {

  val mockProjectRepo: GroupProjectRepository = mock[GroupProjectRepository]
  val mockMemberRepo: GroupProjectMemberRepository = mock[GroupProjectMemberRepository]

  val service = new GroupProjectService(mockProjectRepo, mockMemberRepo)

  override def beforeEach(): Unit = {
    reset(mockProjectRepo, mockMemberRepo)
  }

  val testProject: GroupProject = GroupProject(
    id = Some(1),
    projectGuid = UUID.randomUUID(),
    projectName = "R-CTS4466 Project",
    projectType = "HAPLOGROUP",
    targetHaplogroup = Some("R-CTS4466"),
    targetLineage = Some("Y_DNA"),
    joinPolicy = "APPROVAL_REQUIRED",
    ownerDid = "did:plc:admin1"
  )

  val openProject: GroupProject = testProject.copy(id = Some(2), joinPolicy = "OPEN")

  val adminMember: GroupProjectMember = GroupProjectMember(
    id = Some(1), groupProjectId = 1, citizenDid = "did:plc:admin1",
    role = "ADMIN", status = "ACTIVE", joinedAt = Some(LocalDateTime.now())
  )

  val coAdminMember: GroupProjectMember = GroupProjectMember(
    id = Some(2), groupProjectId = 1, citizenDid = "did:plc:coadmin1",
    role = "CO_ADMIN", status = "ACTIVE", joinedAt = Some(LocalDateTime.now())
  )

  val regularMember: GroupProjectMember = GroupProjectMember(
    id = Some(3), groupProjectId = 1, citizenDid = "did:plc:member1",
    role = "MEMBER", status = "ACTIVE", joinedAt = Some(LocalDateTime.now())
  )

  val pendingMember: GroupProjectMember = GroupProjectMember(
    id = Some(4), groupProjectId = 1, citizenDid = "did:plc:pending1",
    role = "MEMBER", status = "PENDING_APPROVAL"
  )

  "GroupProjectService.createProject" should {

    "create project and add creator as admin" in {
      val newProject = testProject.copy(id = None)
      val createdProject = testProject.copy(id = Some(1))

      when(mockProjectRepo.create(any[GroupProject])).thenReturn(Future.successful(createdProject))
      when(mockMemberRepo.create(any[GroupProjectMember])).thenReturn(Future.successful(adminMember))

      whenReady(service.createProject(newProject, "did:plc:admin1")) { result =>
        result mustBe a[Right[?, ?]]
        result.toOption.get.id mustBe Some(1)
        verify(mockMemberRepo).create(any[GroupProjectMember])
      }
    }

    "reject invalid project type" in {
      val invalid = testProject.copy(id = None, projectType = "INVALID")

      whenReady(service.createProject(invalid, "did:plc:admin1")) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get must include("Invalid project type")
      }
    }

    "reject short project name" in {
      val invalid = testProject.copy(id = None, projectName = "AB")

      whenReady(service.createProject(invalid, "did:plc:admin1")) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get must include("at least 3 characters")
      }
    }

    "reject HAPLOGROUP_VERIFIED without requirement" in {
      val invalid = testProject.copy(id = None, joinPolicy = "HAPLOGROUP_VERIFIED", haplogroupRequirement = None)

      whenReady(service.createProject(invalid, "did:plc:admin1")) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get must include("Haplogroup requirement")
      }
    }
  }

  "GroupProjectService.requestMembership" should {

    "create pending membership for approval-required project" in {
      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(testProject)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:new1")).thenReturn(Future.successful(None))
      when(mockMemberRepo.create(any[GroupProjectMember])).thenReturn(Future.successful(pendingMember))

      whenReady(service.requestMembership(1, "did:plc:new1")) { result =>
        result mustBe a[Right[?, ?]]
      }
    }

    "auto-activate for open project" in {
      when(mockProjectRepo.findById(2)).thenReturn(Future.successful(Some(openProject)))
      when(mockMemberRepo.findByProjectAndCitizen(2, "did:plc:new1")).thenReturn(Future.successful(None))
      when(mockMemberRepo.create(any[GroupProjectMember])).thenAnswer { inv =>
        val m = inv.getArgument[GroupProjectMember](0)
        Future.successful(m.copy(id = Some(10)))
      }

      whenReady(service.requestMembership(2, "did:plc:new1")) { result =>
        result mustBe a[Right[?, ?]]
        result.toOption.get.status mustBe "ACTIVE"
      }
    }

    "reject if already active member" in {
      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(testProject)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:member1"))
        .thenReturn(Future.successful(Some(regularMember)))

      whenReady(service.requestMembership(1, "did:plc:member1")) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get must include("Already a member")
      }
    }

    "reject if previously removed" in {
      val removed = regularMember.copy(status = "REMOVED")
      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(testProject)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:member1"))
        .thenReturn(Future.successful(Some(removed)))

      whenReady(service.requestMembership(1, "did:plc:member1")) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get must include("revoked")
      }
    }

    "return error for non-existent project" in {
      when(mockProjectRepo.findById(99)).thenReturn(Future.successful(None))

      whenReady(service.requestMembership(99, "did:plc:new1")) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get must include("not found")
      }
    }
  }

  "GroupProjectService.approveMembership" should {

    "approve pending membership when admin" in {
      when(mockMemberRepo.findById(4)).thenReturn(Future.successful(Some(pendingMember)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:admin1"))
        .thenReturn(Future.successful(Some(adminMember)))
      when(mockMemberRepo.update(any[GroupProjectMember])).thenReturn(Future.successful(true))

      whenReady(service.approveMembership(4, "did:plc:admin1")) { result =>
        result mustBe a[Right[?, ?]]
        result.toOption.get.status mustBe "ACTIVE"
      }
    }

    "reject if not admin" in {
      when(mockMemberRepo.findById(4)).thenReturn(Future.successful(Some(pendingMember)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:member1"))
        .thenReturn(Future.successful(Some(regularMember)))

      whenReady(service.approveMembership(4, "did:plc:member1")) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get must include("permissions")
      }
    }

    "reject if membership is not pending" in {
      when(mockMemberRepo.findById(3)).thenReturn(Future.successful(Some(regularMember)))

      whenReady(service.approveMembership(3, "did:plc:admin1")) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get must include("Cannot approve")
      }
    }
  }

  "GroupProjectService.leaveProject" should {

    "allow regular member to leave" in {
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:member1"))
        .thenReturn(Future.successful(Some(regularMember)))
      when(mockMemberRepo.updateStatus(3, "LEFT")).thenReturn(Future.successful(true))

      whenReady(service.leaveProject(1, "did:plc:member1")) { result =>
        result mustBe a[Right[?, ?]]
      }
    }

    "prevent admin from leaving" in {
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:admin1"))
        .thenReturn(Future.successful(Some(adminMember)))

      whenReady(service.leaveProject(1, "did:plc:admin1")) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get must include("Admin cannot leave")
      }
    }
  }

  "GroupProjectService.removeMember" should {

    "allow admin to remove member" in {
      when(mockMemberRepo.findById(3)).thenReturn(Future.successful(Some(regularMember)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:admin1"))
        .thenReturn(Future.successful(Some(adminMember)))
      when(mockMemberRepo.updateStatus(3, "REMOVED")).thenReturn(Future.successful(true))

      whenReady(service.removeMember(3, "did:plc:admin1")) { result =>
        result mustBe a[Right[?, ?]]
      }
    }

    "prevent removing the admin" in {
      when(mockMemberRepo.findById(1)).thenReturn(Future.successful(Some(adminMember)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:admin1"))
        .thenReturn(Future.successful(Some(adminMember)))

      whenReady(service.removeMember(1, "did:plc:admin1")) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get must include("Cannot remove the project admin")
      }
    }
  }

  "GroupProjectService.assignRole" should {

    "allow admin to assign co-admin role" in {
      when(mockMemberRepo.findById(3)).thenReturn(Future.successful(Some(regularMember)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:admin1"))
        .thenReturn(Future.successful(Some(adminMember)))
      when(mockMemberRepo.updateRole(3, "CO_ADMIN")).thenReturn(Future.successful(true))

      whenReady(service.assignRole(3, "CO_ADMIN", "did:plc:admin1")) { result =>
        result mustBe a[Right[?, ?]]
      }
    }

    "reject assigning ADMIN role" in {
      when(mockMemberRepo.findById(3)).thenReturn(Future.successful(Some(regularMember)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:admin1"))
        .thenReturn(Future.successful(Some(adminMember)))

      whenReady(service.assignRole(3, "ADMIN", "did:plc:admin1")) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get must include("Cannot assign ADMIN")
      }
    }

    "reject invalid role" in {
      whenReady(service.assignRole(3, "SUPERUSER", "did:plc:admin1")) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get must include("Invalid role")
      }
    }
  }

  "GroupProjectService.getProjectMembers" should {

    "return members when requester has access" in {
      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(testProject)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:member1"))
        .thenReturn(Future.successful(Some(regularMember)))
      when(mockMemberRepo.findByProjectAndStatus(1, "ACTIVE"))
        .thenReturn(Future.successful(Seq(adminMember, regularMember)))

      whenReady(service.getProjectMembers(1, "did:plc:member1")) { result =>
        result mustBe a[Right[?, ?]]
        result.toOption.get.size mustBe 2
      }
    }

    "deny non-member access to MEMBERS_ONLY list" in {
      when(mockProjectRepo.findById(1)).thenReturn(Future.successful(Some(testProject)))
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:outsider"))
        .thenReturn(Future.successful(None))

      whenReady(service.getProjectMembers(1, "did:plc:outsider")) { result =>
        result mustBe a[Left[?, ?]]
        result.left.toOption.get must include("permissions")
      }
    }
  }

  "GroupProjectService.getPendingRequests" should {

    "return pending members when admin" in {
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:admin1"))
        .thenReturn(Future.successful(Some(adminMember)))
      when(mockMemberRepo.findByProjectAndStatus(1, "PENDING_APPROVAL"))
        .thenReturn(Future.successful(Seq(pendingMember)))

      whenReady(service.getPendingRequests(1, "did:plc:admin1")) { result =>
        result mustBe a[Right[?, ?]]
        result.toOption.get.size mustBe 1
      }
    }

    "deny regular member access to pending requests" in {
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:member1"))
        .thenReturn(Future.successful(Some(regularMember)))

      whenReady(service.getPendingRequests(1, "did:plc:member1")) { result =>
        result mustBe a[Left[?, ?]]
      }
    }
  }

  "GroupProjectService.hasPermission" should {

    "grant all permissions to admin" in {
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:admin1"))
        .thenReturn(Future.successful(Some(adminMember)))

      whenReady(service.hasPermission(1, "did:plc:admin1", "APPROVE_MEMBERS")) { _ mustBe true }
    }

    "grant limited permissions to co-admin" in {
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:coadmin1"))
        .thenReturn(Future.successful(Some(coAdminMember)))

      whenReady(service.hasPermission(1, "did:plc:coadmin1", "APPROVE_MEMBERS")) { _ mustBe true }
      whenReady(service.hasPermission(1, "did:plc:coadmin1", "MANAGE_ROLES")) { _ mustBe false }
    }

    "deny permissions to regular member" in {
      when(mockMemberRepo.findByProjectAndCitizen(1, "did:plc:member1"))
        .thenReturn(Future.successful(Some(regularMember)))

      whenReady(service.hasPermission(1, "did:plc:member1", "APPROVE_MEMBERS")) { _ mustBe false }
    }
  }
}
