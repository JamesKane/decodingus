package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Singleton}
import play.api.Logging
import play.api.libs.json.{Json, OFormat}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import repositories.{InstrumentObservationRepository, InstrumentProposalRepository}
import services.InstrumentProposalService
import models.domain.genomics.ProposalStatus

import scala.concurrent.ExecutionContext

@Singleton
class InstrumentProposalController @Inject()(
                                              val controllerComponents: ControllerComponents,
                                              secureApi: ApiSecurityAction,
                                              proposalService: InstrumentProposalService,
                                              proposalRepo: InstrumentProposalRepository,
                                              observationRepo: InstrumentObservationRepository
                                            )(implicit ec: ExecutionContext)
  extends BaseController with Logging {

  // Audit identity for API-key-authenticated actions
  private val ApiCuratorId = "api-system"

  case class AcceptProposalRequest(
                                    labName: String,
                                    manufacturer: Option[String] = None,
                                    model: Option[String] = None,
                                    notes: Option[String] = None
                                  )
  object AcceptProposalRequest { implicit val format: OFormat[AcceptProposalRequest] = Json.format }

  case class RejectProposalRequest(reason: String)
  object RejectProposalRequest { implicit val format: OFormat[RejectProposalRequest] = Json.format }

  def listProposals(status: Option[String]): Action[AnyContent] = secureApi.async { _ =>
    val query = status.flatMap(s => scala.util.Try(ProposalStatus.fromString(s)).toOption) match {
      case Some(s) => proposalRepo.findByStatus(s)
      case None => proposalRepo.findPending()
    }

    query.map { proposals =>
      Ok(Json.obj(
        "proposals" -> proposals,
        "total" -> proposals.size
      ))
    }.recover {
      case e: Exception =>
        logger.error(s"Error listing instrument proposals: ${e.getMessage}", e)
        InternalServerError(Json.obj("error" -> "An internal error occurred."))
    }
  }

  def getProposalDetail(id: Int): Action[AnyContent] = secureApi.async { _ =>
    proposalRepo.findById(id).flatMap {
      case None =>
        scala.concurrent.Future.successful(
          NotFound(Json.obj("error" -> s"Proposal $id not found"))
        )
      case Some(proposal) =>
        observationRepo.findByInstrumentId(proposal.instrumentId).map { observations =>
          Ok(Json.obj(
            "proposal" -> proposal,
            "observations" -> observations,
            "observationCount" -> observations.size,
            "distinctCitizens" -> observations.map(_.biosampleRef).distinct.size
          ))
        }
    }.recover {
      case e: Exception =>
        logger.error(s"Error getting proposal $id: ${e.getMessage}", e)
        InternalServerError(Json.obj("error" -> "An internal error occurred."))
    }
  }

  def acceptProposal(id: Int): Action[AcceptProposalRequest] =
    secureApi.jsonAction[AcceptProposalRequest].async { request =>
      val body = request.body
      proposalService.acceptProposal(id, ApiCuratorId, body.labName, body.manufacturer, body.model, body.notes).map {
        case Right(proposal) => Ok(Json.toJson(proposal))
        case Left(error) => BadRequest(Json.obj("error" -> error))
      }.recover {
        case e: Exception =>
          logger.error(s"Error accepting proposal $id: ${e.getMessage}", e)
          InternalServerError(Json.obj("error" -> "An internal error occurred."))
      }
    }

  def rejectProposal(id: Int): Action[RejectProposalRequest] =
    secureApi.jsonAction[RejectProposalRequest].async { request =>
      val body = request.body
      proposalService.rejectProposal(id, ApiCuratorId, body.reason).map {
        case Right(proposal) => Ok(Json.toJson(proposal))
        case Left(error) => BadRequest(Json.obj("error" -> error))
      }.recover {
        case e: Exception =>
          logger.error(s"Error rejecting proposal $id: ${e.getMessage}", e)
          InternalServerError(Json.obj("error" -> "An internal error occurred."))
      }
    }

  def detectConflicts(): Action[AnyContent] = secureApi.async { _ =>
    proposalService.detectConflicts().map { conflicts =>
      Ok(Json.obj(
        "conflicts" -> conflicts.map { c =>
          Json.obj(
            "instrumentId" -> c.instrumentId,
            "dominantLabName" -> c.dominantLabName,
            "dominantRatio" -> c.dominantRatio,
            "labs" -> c.proposals.map { l =>
              Json.obj(
                "labName" -> l.labName,
                "observationCount" -> l.observationCount,
                "ratio" -> l.ratio
              )
            }
          )
        },
        "total" -> conflicts.size
      ))
    }.recover {
      case e: Exception =>
        logger.error(s"Error detecting conflicts: ${e.getMessage}", e)
        InternalServerError(Json.obj("error" -> "An internal error occurred."))
    }
  }
}
