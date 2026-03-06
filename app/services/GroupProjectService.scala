package services

import jakarta.inject.{Inject, Singleton}
import models.domain.{GroupProject, GroupProjectMember, MemberVisibility}
import play.api.Logging
import repositories.{GroupProjectMemberRepository, GroupProjectRepository}

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class GroupProjectService @Inject()(
                                     projectRepo: GroupProjectRepository,
                                     memberRepo: GroupProjectMemberRepository
                                   )(implicit ec: ExecutionContext) extends Logging {

  def createProject(project: GroupProject, creatorDid: String): Future[Either[String, GroupProject]] = {
    validateProject(project) match {
      case Some(error) => Future.successful(Left(error))
      case None =>
        val toCreate = project.copy(ownerDid = creatorDid)
        projectRepo.create(toCreate).flatMap { created =>
          val adminMember = GroupProjectMember(
            groupProjectId = created.id.get,
            citizenDid = creatorDid,
            role = "ADMIN",
            status = "ACTIVE",
            joinedAt = Some(LocalDateTime.now())
          )
          memberRepo.create(adminMember).map(_ => Right(created))
        }
    }
  }

  def updateProject(projectId: Int, updaterDid: String, updates: GroupProject): Future[Either[String, GroupProject]] = {
    projectRepo.findById(projectId).flatMap {
      case None => Future.successful(Left("Project not found"))
      case Some(existing) =>
        isAdminOrCoAdmin(projectId, updaterDid).flatMap {
          case false => Future.successful(Left("Only admins and co-admins can update projects"))
          case true =>
            val updated = existing.copy(
              projectName = updates.projectName,
              description = updates.description,
              backgroundInfo = updates.backgroundInfo,
              joinPolicy = updates.joinPolicy,
              haplogroupRequirement = updates.haplogroupRequirement,
              memberListVisibility = updates.memberListVisibility,
              strPolicy = updates.strPolicy,
              snpPolicy = updates.snpPolicy,
              publicTreeView = updates.publicTreeView,
              successionPolicy = updates.successionPolicy
            )
            projectRepo.update(updated).map {
              case true => Right(updated)
              case false => Left("Failed to update project")
            }
        }
    }
  }

  def requestMembership(
                          projectId: Int,
                          citizenDid: String,
                          biosampleAtUri: Option[String] = None,
                          displayName: Option[String] = None
                        ): Future[Either[String, GroupProjectMember]] = {
    projectRepo.findById(projectId).flatMap {
      case None => Future.successful(Left("Project not found"))
      case Some(project) =>
        memberRepo.findByProjectAndCitizen(projectId, citizenDid).flatMap {
          case Some(existing) if existing.status == "ACTIVE" =>
            Future.successful(Left("Already a member"))
          case Some(existing) if existing.status == "PENDING_APPROVAL" =>
            Future.successful(Left("Membership request already pending"))
          case Some(existing) if existing.status == "REMOVED" =>
            Future.successful(Left("Membership was revoked by project admin"))
          case _ =>
            val status = if (project.joinPolicy == "OPEN") "ACTIVE" else "PENDING_APPROVAL"
            val joinedAt = if (status == "ACTIVE") Some(LocalDateTime.now()) else None
            val member = GroupProjectMember(
              groupProjectId = projectId,
              citizenDid = citizenDid,
              biosampleAtUri = biosampleAtUri,
              status = status,
              displayName = displayName,
              joinedAt = joinedAt
            )
            memberRepo.create(member).map(m => Right(m))
        }
    }
  }

  def approveMembership(memberId: Int, approverDid: String): Future[Either[String, GroupProjectMember]] = {
    memberRepo.findById(memberId).flatMap {
      case None => Future.successful(Left("Membership not found"))
      case Some(member) if member.status != "PENDING_APPROVAL" =>
        Future.successful(Left(s"Cannot approve membership with status: ${member.status}"))
      case Some(member) =>
        hasPermission(member.groupProjectId, approverDid, "APPROVE_MEMBERS").flatMap {
          case false => Future.successful(Left("Insufficient permissions"))
          case true =>
            val updated = member.copy(status = "ACTIVE", joinedAt = Some(LocalDateTime.now()))
            memberRepo.update(updated).map {
              case true => Right(updated)
              case false => Left("Failed to approve membership")
            }
        }
    }
  }

  def rejectMembership(memberId: Int, rejecterDid: String): Future[Either[String, Boolean]] = {
    memberRepo.findById(memberId).flatMap {
      case None => Future.successful(Left("Membership not found"))
      case Some(member) if member.status != "PENDING_APPROVAL" =>
        Future.successful(Left(s"Cannot reject membership with status: ${member.status}"))
      case Some(member) =>
        hasPermission(member.groupProjectId, rejecterDid, "APPROVE_MEMBERS").flatMap {
          case false => Future.successful(Left("Insufficient permissions"))
          case true =>
            memberRepo.updateStatus(member.id.get, "REMOVED").map(Right(_))
        }
    }
  }

  def removeMember(memberId: Int, removerDid: String): Future[Either[String, Boolean]] = {
    memberRepo.findById(memberId).flatMap {
      case None => Future.successful(Left("Membership not found"))
      case Some(member) =>
        hasPermission(member.groupProjectId, removerDid, "REMOVE_MEMBERS").flatMap {
          case false => Future.successful(Left("Insufficient permissions"))
          case true =>
            if (member.role == "ADMIN") Future.successful(Left("Cannot remove the project admin"))
            else memberRepo.updateStatus(member.id.get, "REMOVED").map(Right(_))
        }
    }
  }

  def leaveProject(projectId: Int, citizenDid: String): Future[Either[String, Boolean]] = {
    memberRepo.findByProjectAndCitizen(projectId, citizenDid).flatMap {
      case None => Future.successful(Left("Not a member of this project"))
      case Some(member) if member.role == "ADMIN" =>
        Future.successful(Left("Admin cannot leave. Transfer ownership first."))
      case Some(member) =>
        memberRepo.updateStatus(member.id.get, "LEFT").map(Right(_))
    }
  }

  def assignRole(memberId: Int, newRole: String, assignerDid: String): Future[Either[String, Boolean]] = {
    if (!GroupProjectMember.ValidRoles.contains(newRole))
      return Future.successful(Left(s"Invalid role: $newRole"))

    memberRepo.findById(memberId).flatMap {
      case None => Future.successful(Left("Membership not found"))
      case Some(member) if member.status != "ACTIVE" =>
        Future.successful(Left("Can only assign roles to active members"))
      case Some(member) =>
        isAdminOrCoAdmin(member.groupProjectId, assignerDid).flatMap {
          case false => Future.successful(Left("Only admins and co-admins can assign roles"))
          case true =>
            if (newRole == "ADMIN") Future.successful(Left("Cannot assign ADMIN role. Use ownership transfer."))
            else memberRepo.updateRole(member.id.get, newRole).map(Right(_))
        }
    }
  }

  def getProjectMembers(projectId: Int, requesterDid: String): Future[Either[String, Seq[GroupProjectMember]]] = {
    projectRepo.findById(projectId).flatMap {
      case None => Future.successful(Left("Project not found"))
      case Some(project) =>
        memberRepo.findByProjectAndCitizen(projectId, requesterDid).flatMap { requesterMembership =>
          val canView = project.memberListVisibility match {
            case "PUBLIC" => true
            case "MEMBERS_ONLY" => requesterMembership.exists(_.status == "ACTIVE")
            case "ADMINS_ONLY" => requesterMembership.exists(m => m.status == "ACTIVE" && Set("ADMIN", "CO_ADMIN").contains(m.role))
            case "HIDDEN" => requesterMembership.exists(m => m.status == "ACTIVE" && m.role == "ADMIN")
            case _ => false
          }
          if (!canView) Future.successful(Left("Insufficient permissions to view member list"))
          else memberRepo.findByProjectAndStatus(projectId, "ACTIVE").map(Right(_))
        }
    }
  }

  def getPendingRequests(projectId: Int, requesterDid: String): Future[Either[String, Seq[GroupProjectMember]]] = {
    hasPermission(projectId, requesterDid, "APPROVE_MEMBERS").flatMap {
      case false => Future.successful(Left("Insufficient permissions"))
      case true => memberRepo.findByProjectAndStatus(projectId, "PENDING_APPROVAL").map(Right(_))
    }
  }

  private def validateProject(project: GroupProject): Option[String] = {
    if (project.projectName.isBlank || project.projectName.length < 3)
      Some("Project name must be at least 3 characters")
    else if (project.projectName.length > 100)
      Some("Project name must be at most 100 characters")
    else if (!GroupProject.ValidProjectTypes.contains(project.projectType))
      Some(s"Invalid project type: ${project.projectType}")
    else if (!GroupProject.ValidJoinPolicies.contains(project.joinPolicy))
      Some(s"Invalid join policy: ${project.joinPolicy}")
    else if (project.joinPolicy == "HAPLOGROUP_VERIFIED" && project.haplogroupRequirement.isEmpty)
      Some("Haplogroup requirement is required for HAPLOGROUP_VERIFIED join policy")
    else if (project.targetLineage.exists(!GroupProject.ValidLineages.contains(_)))
      Some(s"Invalid target lineage: ${project.targetLineage.get}")
    else
      None
  }

  private[services] def isAdminOrCoAdmin(projectId: Int, citizenDid: String): Future[Boolean] = {
    memberRepo.findByProjectAndCitizen(projectId, citizenDid).map {
      case Some(m) => m.status == "ACTIVE" && Set("ADMIN", "CO_ADMIN").contains(m.role)
      case None => false
    }
  }

  private[services] def hasPermission(projectId: Int, citizenDid: String, permission: String): Future[Boolean] = {
    memberRepo.findByProjectAndCitizen(projectId, citizenDid).map {
      case Some(m) if m.status == "ACTIVE" =>
        m.role match {
          case "ADMIN" => true
          case "CO_ADMIN" => Set("APPROVE_MEMBERS", "REMOVE_MEMBERS", "EDIT_PROJECT", "MANAGE_SUBGROUPS", "SEND_ANNOUNCEMENTS").contains(permission)
          case "MODERATOR" => Set("APPROVE_MEMBERS", "REMOVE_MEMBERS", "SEND_ANNOUNCEMENTS").contains(permission)
          case "CURATOR" => Set("MANAGE_SUBGROUPS").contains(permission)
          case _ => false
        }
      case _ => false
    }
  }
}
