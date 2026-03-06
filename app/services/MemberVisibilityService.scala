package services

import jakarta.inject.{Inject, Singleton}
import models.domain.*
import play.api.Logging
import repositories.{GroupProjectMemberRepository, GroupProjectRepository}

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class MemberVisibilityService @Inject()(
                                         projectRepo: GroupProjectRepository,
                                         memberRepo: GroupProjectMemberRepository
                                       )(implicit ec: ExecutionContext) extends Logging {

  def updateVisibility(
                        memberId: Int,
                        requesterDid: String,
                        newVisibility: MemberVisibility
                      ): Future[Either[String, GroupProjectMember]] = {
    memberRepo.findById(memberId).flatMap {
      case None => Future.successful(Left("Membership not found"))
      case Some(member) if member.citizenDid != requesterDid =>
        Future.successful(Left("Only the member can update their own visibility"))
      case Some(member) if member.status != "ACTIVE" =>
        Future.successful(Left("Can only update visibility for active memberships"))
      case Some(member) =>
        validateVisibility(newVisibility) match {
          case Some(error) => Future.successful(Left(error))
          case None =>
            val updated = member.copy(visibility = newVisibility, updatedAt = LocalDateTime.now())
            memberRepo.update(updated).map {
              case true => Right(updated)
              case false => Left("Failed to update visibility")
            }
        }
    }
  }

  def getEffectiveVisibility(memberId: Int): Future[Option[EffectiveVisibility]] = {
    memberRepo.findById(memberId).flatMap {
      case None => Future.successful(None)
      case Some(member) =>
        projectRepo.findById(member.groupProjectId).map {
          case None => None
          case Some(project) => Some(EffectiveVisibility.compute(project, member.visibility))
        }
    }
  }

  def getFilteredMemberView(
                              memberId: Int,
                              viewerDid: String,
                              haplogroup: Option[String] = None,
                              lineagePath: Option[Seq[String]] = None,
                              privateVariantCount: Option[Int] = None,
                              ancestor: AncestorData = AncestorData()
                            ): Future[Option[FilteredMemberView]] = {
    memberRepo.findById(memberId).flatMap {
      case None => Future.successful(None)
      case Some(member) if member.status != "ACTIVE" => Future.successful(None)
      case Some(member) =>
        projectRepo.findById(member.groupProjectId).flatMap {
          case None => Future.successful(None)
          case Some(project) =>
            val effective = EffectiveVisibility.compute(project, member.visibility)
            resolveViewerContext(project, member.groupProjectId, viewerDid).map { context =>
              Some(buildFilteredView(member, effective, context, haplogroup, lineagePath, privateVariantCount, ancestor))
            }
        }
    }
  }

  def getFilteredMembersForProject(
                                     projectId: Int,
                                     viewerDid: String,
                                     memberData: Map[Int, MemberSupplementalData]
                                   ): Future[Either[String, Seq[FilteredMemberView]]] = {
    projectRepo.findById(projectId).flatMap {
      case None => Future.successful(Left("Project not found"))
      case Some(project) =>
        for {
          viewerContext <- resolveViewerContext(project, projectId, viewerDid)
          members <- memberRepo.findByProjectAndStatus(projectId, "ACTIVE")
        } yield {
          if (!canViewMemberList(project, viewerContext))
            Left("Insufficient permissions to view member list")
          else {
            val views = members.flatMap { member =>
              val effective = EffectiveVisibility.compute(project, member.visibility)
              if (!effective.showInMemberList && !viewerContext.isAdmin) None
              else {
                val data = memberData.getOrElse(member.id.getOrElse(-1), MemberSupplementalData())
                Some(buildFilteredView(member, effective, viewerContext,
                  data.haplogroup, data.lineagePath, data.privateVariantCount, data.ancestor))
              }
            }
            Right(views)
          }
        }
    }
  }

  private def buildFilteredView(
                                  member: GroupProjectMember,
                                  effective: EffectiveVisibility,
                                  context: ViewerContext,
                                  haplogroup: Option[String],
                                  lineagePath: Option[Seq[String]],
                                  privateVariantCount: Option[Int],
                                  ancestor: AncestorData
                                ): FilteredMemberView = {
    FilteredMemberView(
      memberId = member.id.getOrElse(0),
      kitId = member.kitId.orElse(Some(s"KIT-${member.id.getOrElse(0)}")),
      displayName = if (effective.showDisplayName) member.displayName else None,
      role = member.role,
      contributionLevel = member.contributionLevel,
      terminalHaplogroup = if (effective.shareTerminalHaplogroup) haplogroup else None,
      lineagePath = if (effective.shareFullLineagePath) lineagePath else None,
      privateVariantCount = if (effective.sharePrivateVariants) privateVariantCount else None,
      ancestor = AncestorData.filter(ancestor, effective.ancestorVisibility),
      strVisibility = effective.strVisibility,
      allowDirectContact = effective.allowDirectContact && context.isMember,
      subgroupIds = member.subgroupIds,
      joinedAt = member.joinedAt
    )
  }

  private def canViewMemberList(project: GroupProject, context: ViewerContext): Boolean = {
    project.memberListVisibility match {
      case "PUBLIC" => true
      case "MEMBERS_ONLY" => context.isMember
      case "ADMINS_ONLY" => context.isAdmin
      case "HIDDEN" => context.isAdmin && context.role == "ADMIN"
      case _ => false
    }
  }

  private[services] def resolveViewerContext(project: GroupProject, projectId: Int, viewerDid: String): Future[ViewerContext] = {
    memberRepo.findByProjectAndCitizen(projectId, viewerDid).map {
      case Some(m) if m.status == "ACTIVE" =>
        ViewerContext(
          isMember = true,
          isAdmin = Set("ADMIN", "CO_ADMIN").contains(m.role),
          role = m.role,
          viewerDid = viewerDid
        )
      case _ =>
        ViewerContext(isMember = false, isAdmin = false, role = "NONE", viewerDid = viewerDid)
    }
  }

  private def validateVisibility(v: MemberVisibility): Option[String] = {
    if (!MemberVisibility.ValidAncestorVisibility.contains(v.ancestorVisibility))
      Some(s"Invalid ancestor visibility: ${v.ancestorVisibility}")
    else if (!MemberVisibility.ValidStrVisibility.contains(v.strVisibility))
      Some(s"Invalid STR visibility: ${v.strVisibility}")
    else
      None
  }
}

case class ViewerContext(
                          isMember: Boolean,
                          isAdmin: Boolean,
                          role: String,
                          viewerDid: String
                        )

case class MemberSupplementalData(
                                    haplogroup: Option[String] = None,
                                    lineagePath: Option[Seq[String]] = None,
                                    privateVariantCount: Option[Int] = None,
                                    ancestor: AncestorData = AncestorData()
                                  )
