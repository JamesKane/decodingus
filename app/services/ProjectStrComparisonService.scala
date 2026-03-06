package services

import jakarta.inject.{Inject, Singleton}
import models.dal.domain.genomics.StrMutationRate
import models.domain.{EffectiveVisibility, GroupProject, GroupProjectMember}
import play.api.Logging
import play.api.libs.json.{Json, OFormat}
import repositories.*

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

case class StrDistanceResult(
                              memberId1: Int,
                              memberId2: Int,
                              geneticDistance: Int,
                              markerCount: Int,
                              normalizedDistance: Double
                            )

object StrDistanceResult {
  implicit val format: OFormat[StrDistanceResult] = Json.format[StrDistanceResult]
}

case class ModalComparisonResult(
                                   memberId: Int,
                                   distanceFromModal: Int,
                                   markerCount: Int,
                                   normalizedDistance: Double
                                 )

object ModalComparisonResult {
  implicit val format: OFormat[ModalComparisonResult] = Json.format[ModalComparisonResult]
}

case class ProjectModalHaplotype(
                                   projectId: Int,
                                   markerModals: Seq[MarkerModal],
                                   sampleCount: Int,
                                   computedAt: LocalDateTime = LocalDateTime.now()
                                 )

object ProjectModalHaplotype {
  implicit val format: OFormat[ProjectModalHaplotype] = Json.format[ProjectModalHaplotype]
}

case class MarkerModal(
                        markerName: String,
                        modalValue: Int,
                        variance: Double,
                        sampleCount: Int
                      )

object MarkerModal {
  implicit val format: OFormat[MarkerModal] = Json.format[MarkerModal]
}

@Singleton
class ProjectStrComparisonService @Inject()(
                                             projectRepo: GroupProjectRepository,
                                             memberRepo: GroupProjectMemberRepository,
                                             biosampleRepo: CitizenBiosampleRepository,
                                             biosampleMainRepo: BiosampleRepository,
                                             variantCallRepo: BiosampleVariantCallRepository,
                                             strMutationRateRepo: StrMutationRateRepository
                                           )(implicit ec: ExecutionContext) extends Logging {

  def getProjectModalHaplotype(
                                 projectId: Int,
                                 viewerDid: String
                               ): Future[Either[String, ProjectModalHaplotype]] = {
    projectRepo.findById(projectId).flatMap {
      case None => Future.successful(Left("Project not found"))
      case Some(project) =>
        resolveViewerPermission(project, projectId, viewerDid, "MODAL_COMPARISON_ONLY").flatMap {
          case Left(err) => Future.successful(Left(err))
          case Right(_) =>
            computeProjectModal(project).map(Right(_))
        }
    }
  }

  def getMemberDistanceFromModal(
                                   projectId: Int,
                                   memberId: Int,
                                   viewerDid: String
                                 ): Future[Either[String, ModalComparisonResult]] = {
    projectRepo.findById(projectId).flatMap {
      case None => Future.successful(Left("Project not found"))
      case Some(project) =>
        resolveViewerPermission(project, projectId, viewerDid, "DISTANCE_CALCULATION_ONLY").flatMap {
          case Left(err) => Future.successful(Left(err))
          case Right(_) =>
            memberRepo.findById(memberId).flatMap {
              case None => Future.successful(Left("Member not found"))
              case Some(member) if member.groupProjectId != projectId =>
                Future.successful(Left("Member not in this project"))
              case Some(member) if member.status != "ACTIVE" =>
                Future.successful(Left("Member is not active"))
              case Some(member) =>
                val effective = EffectiveVisibility.compute(project, member.visibility)
                if (effective.strVisibility == "NONE")
                  Future.successful(Left("Member STR data is not shared"))
                else
                  computeDistanceFromModal(project, member)
            }
        }
    }
  }

  def getMemberPairDistance(
                             projectId: Int,
                             memberId1: Int,
                             memberId2: Int,
                             viewerDid: String
                           ): Future[Either[String, StrDistanceResult]] = {
    projectRepo.findById(projectId).flatMap {
      case None => Future.successful(Left("Project not found"))
      case Some(project) =>
        resolveViewerPermission(project, projectId, viewerDid, "DISTANCE_CALCULATION_ONLY").flatMap {
          case Left(err) => Future.successful(Left(err))
          case Right(_) =>
            for {
              m1Opt <- memberRepo.findById(memberId1)
              m2Opt <- memberRepo.findById(memberId2)
              result <- (m1Opt, m2Opt) match {
                case (None, _) => Future.successful(Left("Member 1 not found"))
                case (_, None) => Future.successful(Left("Member 2 not found"))
                case (Some(m1), Some(m2)) =>
                  validateMembersForComparison(project, m1, m2, projectId) match {
                    case Some(err) => Future.successful(Left(err))
                    case None => computePairDistance(m1, m2)
                  }
              }
            } yield result
        }
    }
  }

  def getDistanceMatrix(
                          projectId: Int,
                          viewerDid: String
                        ): Future[Either[String, Seq[StrDistanceResult]]] = {
    projectRepo.findById(projectId).flatMap {
      case None => Future.successful(Left("Project not found"))
      case Some(project) =>
        resolveViewerPermission(project, projectId, viewerDid, "DISTANCE_CALCULATION_ONLY").flatMap {
          case Left(err) => Future.successful(Left(err))
          case Right(_) =>
            memberRepo.findByProjectAndStatus(projectId, "ACTIVE").flatMap { members =>
              val eligible = members.filter { m =>
                val effective = EffectiveVisibility.compute(project, m.visibility)
                effective.strVisibility != "NONE"
              }
              computeDistanceMatrix(eligible)
            }
        }
    }
  }

  private[services] def computeProjectModal(project: GroupProject): Future[ProjectModalHaplotype] = {
    for {
      members <- memberRepo.findByProjectAndStatus(project.id.get, "ACTIVE")
      eligible = members.filter { m =>
        val effective = EffectiveVisibility.compute(project, m.visibility)
        effective.strVisibility != "NONE"
      }
      strDataByMember <- collectStrData(eligible)
    } yield {
      val allObservations = strDataByMember.values.flatten.toSeq
      val byMarker = allObservations.groupBy(_._1)

      val markerModals = byMarker.map { case (markerName, observations) =>
        val values = observations.map(_._2)
        val mode = values.groupBy(identity).maxByOption(_._2.size).map(_._1).getOrElse(0)
        val mean = if (values.nonEmpty) values.sum.toDouble / values.size else 0.0
        val variance = if (values.size > 1) {
          values.map(v => math.pow(v - mean, 2)).sum / (values.size - 1)
        } else 0.0

        MarkerModal(markerName, mode, variance, values.size)
      }.toSeq.sortBy(_.markerName)

      ProjectModalHaplotype(
        projectId = project.id.get,
        markerModals = markerModals,
        sampleCount = strDataByMember.size
      )
    }
  }

  private def computeDistanceFromModal(
                                         project: GroupProject,
                                         member: GroupProjectMember
                                       ): Future[Either[String, ModalComparisonResult]] = {
    for {
      modal <- computeProjectModal(project)
      memberStr <- resolveMemberStrData(member)
    } yield {
      memberStr match {
        case None => Left("No STR data available for member")
        case Some(memberValues) =>
          val modalMap = modal.markerModals.map(m => m.markerName -> m.modalValue).toMap
          val commonMarkers = modalMap.keySet.intersect(memberValues.keySet)
          if (commonMarkers.isEmpty)
            Left("No common STR markers between member and project modal")
          else {
            val distance = commonMarkers.toSeq.map { marker =>
              math.abs(modalMap(marker) - memberValues(marker))
            }.sum
            val normalized = if (commonMarkers.nonEmpty) distance.toDouble / commonMarkers.size else 0.0
            Right(ModalComparisonResult(
              memberId = member.id.getOrElse(0),
              distanceFromModal = distance,
              markerCount = commonMarkers.size,
              normalizedDistance = normalized
            ))
          }
      }
    }
  }

  private def computePairDistance(
                                   m1: GroupProjectMember,
                                   m2: GroupProjectMember
                                 ): Future[Either[String, StrDistanceResult]] = {
    for {
      str1 <- resolveMemberStrData(m1)
      str2 <- resolveMemberStrData(m2)
    } yield {
      (str1, str2) match {
        case (None, _) => Left("No STR data available for member 1")
        case (_, None) => Left("No STR data available for member 2")
        case (Some(values1), Some(values2)) =>
          val commonMarkers = values1.keySet.intersect(values2.keySet)
          if (commonMarkers.isEmpty)
            Left("No common STR markers between members")
          else {
            val distance = commonMarkers.toSeq.map { marker =>
              math.abs(values1(marker) - values2(marker))
            }.sum
            val normalized = if (commonMarkers.nonEmpty) distance.toDouble / commonMarkers.size else 0.0
            Right(StrDistanceResult(
              memberId1 = m1.id.getOrElse(0),
              memberId2 = m2.id.getOrElse(0),
              geneticDistance = distance,
              markerCount = commonMarkers.size,
              normalizedDistance = normalized
            ))
          }
      }
    }
  }

  private def computeDistanceMatrix(members: Seq[GroupProjectMember]): Future[Either[String, Seq[StrDistanceResult]]] = {
    collectStrData(members).map { strDataByMember =>
      val memberIds = strDataByMember.keys.toSeq.sorted
      val results = for {
        i <- memberIds.indices
        j <- (i + 1) until memberIds.size
        id1 = memberIds(i)
        id2 = memberIds(j)
        values1 = strDataByMember(id1).toMap
        values2 = strDataByMember(id2).toMap
        commonMarkers = values1.keySet.intersect(values2.keySet)
        if commonMarkers.nonEmpty
      } yield {
        val distance = commonMarkers.toSeq.map(m => math.abs(values1(m) - values2(m))).sum
        val normalized = distance.toDouble / commonMarkers.size
        StrDistanceResult(id1, id2, distance, commonMarkers.size, normalized)
      }
      Right(results)
    }
  }

  private[services] def resolveMemberStrData(member: GroupProjectMember): Future[Option[Map[String, Int]]] = {
    member.biosampleAtUri match {
      case None => Future.successful(None)
      case Some(atUri) =>
        biosampleRepo.findByAtUri(atUri).flatMap {
          case None => Future.successful(None)
          case Some(citizenBiosample) =>
            biosampleMainRepo.findByGuid(citizenBiosample.sampleGuid).flatMap {
              case None => Future.successful(None)
              case Some((biosample, _)) =>
                biosample.id match {
                  case None => Future.successful(None)
                  case Some(biosampleId) =>
                    for {
                      calls <- variantCallRepo.findByBiosample(biosampleId)
                      rates <- strMutationRateRepo.findAll()
                    } yield {
                      val strMarkerNames = rates.map(_.markerName).toSet
                      val strCalls = calls.filter(c =>
                        c.observedState.forall(_.isDigit) && c.observedState.nonEmpty
                      )
                      if (strCalls.isEmpty) None
                      else {
                        val markerMap = strCalls.flatMap { call =>
                          call.observedState.toIntOption.map(v => call.variantId.toString -> v)
                        }.toMap
                        if (markerMap.nonEmpty) Some(markerMap) else None
                      }
                    }
                }
            }
        }
    }
  }

  private def collectStrData(members: Seq[GroupProjectMember]): Future[Map[Int, Seq[(String, Int)]]] = {
    val futures = members.flatMap { m =>
      m.id.map { memberId =>
        resolveMemberStrData(m).map {
          case None => None
          case Some(data) => Some(memberId -> data.toSeq)
        }
      }
    }
    Future.sequence(futures).map(_.flatten.toMap)
  }

  private def validateMembersForComparison(
                                            project: GroupProject,
                                            m1: GroupProjectMember,
                                            m2: GroupProjectMember,
                                            projectId: Int
                                          ): Option[String] = {
    if (m1.groupProjectId != projectId) Some("Member 1 not in this project")
    else if (m2.groupProjectId != projectId) Some("Member 2 not in this project")
    else if (m1.status != "ACTIVE") Some("Member 1 is not active")
    else if (m2.status != "ACTIVE") Some("Member 2 is not active")
    else {
      val eff1 = EffectiveVisibility.compute(project, m1.visibility)
      val eff2 = EffectiveVisibility.compute(project, m2.visibility)
      if (eff1.strVisibility == "NONE") Some("Member 1 STR data is not shared")
      else if (eff2.strVisibility == "NONE") Some("Member 2 STR data is not shared")
      else None
    }
  }

  private def resolveViewerPermission(
                                        project: GroupProject,
                                        projectId: Int,
                                        viewerDid: String,
                                        requiredLevel: String
                                      ): Future[Either[String, Unit]] = {
    val projectStrLevel = project.strPolicy match {
      case "HIDDEN" => "NONE"
      case "DISTANCE_ONLY" => "DISTANCE_CALCULATION_ONLY"
      case "MODAL_COMPARISON" => "MODAL_COMPARISON_ONLY"
      case "MEMBERS_ONLY_RAW" => "FULL_TO_MEMBERS"
      case "PUBLIC_RAW" => "FULL_PUBLIC"
      case _ => "NONE"
    }

    if (projectStrLevel == "NONE")
      return Future.successful(Left("Project STR policy does not allow STR operations"))

    val strRank = Map(
      "NONE" -> 0, "DISTANCE_CALCULATION_ONLY" -> 1, "MODAL_COMPARISON_ONLY" -> 2,
      "FULL_TO_MEMBERS" -> 3, "FULL_PUBLIC" -> 4
    )
    val projectRank = strRank.getOrElse(projectStrLevel, 0)
    val requiredRank = strRank.getOrElse(requiredLevel, 0)

    if (projectRank < requiredRank)
      return Future.successful(Left("Project STR policy does not allow this operation"))

    if (projectStrLevel == "FULL_PUBLIC")
      Future.successful(Right(()))
    else {
      memberRepo.findByProjectAndCitizen(projectId, viewerDid).map {
        case Some(m) if m.status == "ACTIVE" => Right(())
        case _ => Left("Only active project members can access STR data")
      }
    }
  }
}
