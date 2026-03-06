package services

import jakarta.inject.{Inject, Singleton}
import models.HaplogroupType
import models.domain.{EffectiveVisibility, GroupProject, GroupProjectMember}
import models.domain.haplogroups.Haplogroup
import play.api.Logging
import play.api.libs.json.{Json, OFormat}
import repositories.{BiosampleHaplogroupRepository, CitizenBiosampleRepository, GroupProjectMemberRepository, GroupProjectRepository, HaplogroupCoreRepository}

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

case class AggregatedTreeNode(
                               haplogroupId: Int,
                               haplogroupName: String,
                               memberCount: Int,
                               cumulativeCount: Int,
                               children: Seq[AggregatedTreeNode],
                               formedYbp: Option[Int] = None,
                               tmrcaYbp: Option[Int] = None
                             )

object AggregatedTreeNode {
  implicit val format: OFormat[AggregatedTreeNode] = Json.format[AggregatedTreeNode]
}

case class ProjectTreeSummary(
                               projectId: Int,
                               projectName: String,
                               lineageType: String,
                               rootNodes: Seq[AggregatedTreeNode],
                               totalMembers: Int,
                               membersWithHaplogroup: Int,
                               generatedAt: LocalDateTime = LocalDateTime.now()
                             )

object ProjectTreeSummary {
  implicit val format: OFormat[ProjectTreeSummary] = Json.format[ProjectTreeSummary]
}

@Singleton
class ProjectTreeAggregationService @Inject()(
                                               projectRepo: GroupProjectRepository,
                                               memberRepo: GroupProjectMemberRepository,
                                               biosampleRepo: CitizenBiosampleRepository,
                                               biosampleHaplogroupRepo: BiosampleHaplogroupRepository,
                                               haplogroupRepo: HaplogroupCoreRepository
                                             )(implicit ec: ExecutionContext) extends Logging {

  def getAggregatedTree(
                          projectId: Int,
                          lineageType: String,
                          viewerDid: String
                        ): Future[Either[String, ProjectTreeSummary]] = {
    val haplogroupType = lineageType match {
      case "Y_DNA" => Some(HaplogroupType.Y)
      case "MT_DNA" => Some(HaplogroupType.MT)
      case _ => None
    }

    haplogroupType match {
      case None => Future.successful(Left(s"Invalid lineage type: $lineageType"))
      case Some(hgType) =>
        projectRepo.findById(projectId).flatMap {
          case None => Future.successful(Left("Project not found"))
          case Some(project) =>
            canViewTree(project, projectId, viewerDid).flatMap {
              case false => Future.successful(Left("Insufficient permissions to view project tree"))
              case true =>
                buildAggregatedTree(project, hgType).map(Right(_))
            }
        }
    }
  }

  def getBranchMemberCount(
                             projectId: Int,
                             haplogroupId: Int,
                             lineageType: String
                           ): Future[Either[String, Int]] = {
    val haplogroupType = lineageType match {
      case "Y_DNA" => Some(HaplogroupType.Y)
      case "MT_DNA" => Some(HaplogroupType.MT)
      case _ => None
    }

    haplogroupType match {
      case None => Future.successful(Left(s"Invalid lineage type: $lineageType"))
      case Some(hgType) =>
        projectRepo.findById(projectId).flatMap {
          case None => Future.successful(Left("Project not found"))
          case Some(project) =>
            resolveHaplogroupAssignments(project, hgType).flatMap { assignments =>
              haplogroupRepo.getDescendants(haplogroupId).map { descendants =>
                val descendantIds = descendants.flatMap(_.id).toSet + haplogroupId
                val count = assignments.count(hgId => descendantIds.contains(hgId))
                Right(count)
              }
            }
        }
    }
  }

  private def buildAggregatedTree(project: GroupProject, hgType: HaplogroupType): Future[ProjectTreeSummary] = {
    for {
      members <- memberRepo.findByProjectAndStatus(project.id.get, "ACTIVE")
      treeEligibleMembers = members.filter { m =>
        val effective = EffectiveVisibility.compute(project, m.visibility)
        effective.showInTree
      }
      assignments <- resolveHaplogroupAssignments(project, hgType, treeEligibleMembers)
      haplogroupCounts = assignments.groupBy(identity).map { case (hgId, occurrences) => hgId -> occurrences.size }
      rootNodes <- buildTreeFromCounts(haplogroupCounts, hgType)
    } yield ProjectTreeSummary(
      projectId = project.id.get,
      projectName = project.projectName,
      lineageType = if (hgType == HaplogroupType.Y) "Y_DNA" else "MT_DNA",
      rootNodes = rootNodes,
      totalMembers = treeEligibleMembers.size,
      membersWithHaplogroup = assignments.size
    )
  }

  private[services] def resolveHaplogroupAssignments(
                                                      project: GroupProject,
                                                      hgType: HaplogroupType,
                                                      members: Seq[GroupProjectMember]
                                                    ): Future[Seq[Int]] = {
    val biosampleFutures = members.flatMap(_.biosampleAtUri).map { atUri =>
      biosampleRepo.findByAtUri(atUri).flatMap {
        case None => Future.successful(None)
        case Some(biosample) =>
          biosampleHaplogroupRepo.findBySampleGuid(biosample.sampleGuid).map {
            case None => None
            case Some(bh) =>
              hgType match {
                case HaplogroupType.Y => bh.yHaplogroupId
                case HaplogroupType.MT => bh.mtHaplogroupId
              }
          }
      }
    }
    Future.sequence(biosampleFutures).map(_.flatten)
  }

  private def resolveHaplogroupAssignments(
                                            project: GroupProject,
                                            hgType: HaplogroupType
                                          ): Future[Seq[Int]] = {
    for {
      members <- memberRepo.findByProjectAndStatus(project.id.get, "ACTIVE")
      treeEligible = members.filter { m =>
        EffectiveVisibility.compute(project, m.visibility).showInTree
      }
      assignments <- resolveHaplogroupAssignments(project, hgType, treeEligible)
    } yield assignments
  }

  private[services] def buildTreeFromCounts(
                                             haplogroupCounts: Map[Int, Int],
                                             hgType: HaplogroupType
                                           ): Future[Seq[AggregatedTreeNode]] = {
    if (haplogroupCounts.isEmpty) return Future.successful(Seq.empty)

    for {
      // Get ancestor paths for all haplogroups
      pathsWithHaplogroups <- Future.sequence(
        haplogroupCounts.keys.toSeq.map { hgId =>
          for {
            haplogroup <- haplogroupRepo.findById(hgId)
            ancestors <- haplogroupRepo.getAncestors(hgId)
          } yield (hgId, haplogroup, ancestors)
        }
      )

      // Build a set of all haplogroup IDs on any path (ancestors + direct)
      allHaplogroupIds = pathsWithHaplogroups.flatMap { case (hgId, hg, ancestors) =>
        ancestors.flatMap(_.id) :+ hgId
      }.distinct

      // Get all relationships for the haplogroup type
      relationships <- haplogroupRepo.getAllRelationships(hgType)

      // Filter relationships to only include relevant nodes
      relevantRelationships = relationships.filter { case (childId, parentId) =>
        allHaplogroupIds.contains(childId) && allHaplogroupIds.contains(parentId)
      }

      // Build parent->children map
      childrenMap = relevantRelationships.groupBy(_._2).map { case (parentId, rels) =>
        parentId -> rels.map(_._1)
      }

      // Build haplogroup lookup from paths data
      haplogroupLookup = pathsWithHaplogroups.flatMap { case (_, hg, ancestors) =>
        hg.map(h => h.id.get -> h).toSeq ++ ancestors.flatMap(a => a.id.map(_ -> a))
      }.toMap

      // Find root nodes (those in our set that have no parent in our set)
      childIds = relevantRelationships.map(_._1).toSet
      rootIds = allHaplogroupIds.filterNot(childIds.contains)
    } yield {
      rootIds.flatMap(rootId => buildNode(rootId, haplogroupCounts, childrenMap, haplogroupLookup))
    }
  }

  private def buildNode(
                          haplogroupId: Int,
                          directCounts: Map[Int, Int],
                          childrenMap: Map[Int, Seq[Int]],
                          haplogroupLookup: Map[Int, Haplogroup]
                        ): Option[AggregatedTreeNode] = {
    haplogroupLookup.get(haplogroupId).map { haplogroup =>
      val childNodes = childrenMap.getOrElse(haplogroupId, Seq.empty)
        .flatMap(childId => buildNode(childId, directCounts, childrenMap, haplogroupLookup))
        .sortBy(_.haplogroupName)

      val directCount = directCounts.getOrElse(haplogroupId, 0)
      val cumulativeCount = directCount + childNodes.map(_.cumulativeCount).sum

      AggregatedTreeNode(
        haplogroupId = haplogroupId,
        haplogroupName = haplogroup.name,
        memberCount = directCount,
        cumulativeCount = cumulativeCount,
        children = childNodes,
        formedYbp = haplogroup.formedYbp,
        tmrcaYbp = haplogroup.tmrcaYbp
      )
    }
  }

  private def canViewTree(project: GroupProject, projectId: Int, viewerDid: String): Future[Boolean] = {
    if (project.publicTreeView) Future.successful(true)
    else {
      memberRepo.findByProjectAndCitizen(projectId, viewerDid).map {
        case Some(m) if m.status == "ACTIVE" => true
        case _ => false
      }
    }
  }
}
