package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Singleton}
import models.HaplogroupType
import models.domain.discovery.ProposedBranchStatus
import play.api.Logging
import play.api.libs.json.{Json, OFormat}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import services.{DiscoveryProposalService, TreeEvolutionService}

import scala.concurrent.{ExecutionContext, Future}

/**
 * API controller for the Haplogroup Discovery system.
 * Provides endpoints for listing/viewing proposals and curator actions (accept/reject).
 */
@Singleton
class DiscoveryApiController @Inject()(
  val controllerComponents: ControllerComponents,
  secureApi: ApiSecurityAction,
  discoveryService: DiscoveryProposalService,
  treeEvolutionService: TreeEvolutionService
)(implicit ec: ExecutionContext) extends BaseController with Logging {

  // Audit identity for API-key-authenticated actions
  private val ApiCuratorId = "api-system"

  // Request DTOs
  case class AcceptProposalRequest(proposedName: String, reason: Option[String] = None)
  object AcceptProposalRequest { implicit val format: OFormat[AcceptProposalRequest] = Json.format }

  case class RejectProposalRequest(reason: String)
  object RejectProposalRequest { implicit val format: OFormat[RejectProposalRequest] = Json.format }

  /**
   * List proposals with optional filters.
   * GET /api/v1/discovery/proposals?type=Y&status=READY_FOR_REVIEW
   */
  def listProposals(
    haplogroupType: Option[String],
    status: Option[String]
  ): Action[AnyContent] = secureApi.async { _ =>
    val hgType = haplogroupType.flatMap(parseHaplogroupType)
    val pbStatus = status.flatMap(ProposedBranchStatus.fromString)

    discoveryService.listProposals(hgType, pbStatus).map { proposals =>
      Ok(Json.obj(
        "proposals" -> proposals,
        "total" -> proposals.size
      ))
    }.recover {
      case e: Exception =>
        logger.error(s"Error listing proposals: ${e.getMessage}", e)
        InternalServerError(Json.obj("error" -> "An internal error occurred."))
    }
  }

  /**
   * Get proposal details with variants, evidence, and audit trail.
   * GET /api/v1/discovery/proposals/:id
   */
  def getProposalDetails(id: Int): Action[AnyContent] = secureApi.async { _ =>
    discoveryService.getProposalDetails(id).map {
      case Some(details) => Ok(Json.toJson(details))
      case None => NotFound(Json.obj("error" -> s"Proposal $id not found"))
    }.recover {
      case e: Exception =>
        logger.error(s"Error getting proposal $id: ${e.getMessage}", e)
        InternalServerError(Json.obj("error" -> "An internal error occurred."))
    }
  }

  /**
   * Start review of a proposal.
   * POST /api/v1/discovery/proposals/:id/start-review
   */
  def startReview(id: Int): Action[AnyContent] =
    secureApi.async { request =>
      discoveryService.startReview(id, ApiCuratorId).map { proposal =>
        Ok(Json.toJson(proposal))
      }.recover {
        case e: NoSuchElementException =>
          NotFound(Json.obj("error" -> e.getMessage))
        case e: IllegalStateException =>
          BadRequest(Json.obj("error" -> e.getMessage))
        case e: Exception =>
          logger.error(s"Error starting review for proposal $id: ${e.getMessage}", e)
          InternalServerError(Json.obj("error" -> "An internal error occurred."))
      }
    }

  /**
   * Accept a proposal.
   * POST /api/v1/discovery/proposals/:id/accept
   */
  def acceptProposal(id: Int): Action[AcceptProposalRequest] =
    secureApi.jsonAction[AcceptProposalRequest].async { request =>
      val body = request.body
      discoveryService.acceptProposal(id, ApiCuratorId, body.proposedName, body.reason).map { proposal =>
        Ok(Json.toJson(proposal))
      }.recover {
        case e: NoSuchElementException =>
          NotFound(Json.obj("error" -> e.getMessage))
        case e: IllegalStateException =>
          BadRequest(Json.obj("error" -> e.getMessage))
        case e: Exception =>
          logger.error(s"Error accepting proposal $id: ${e.getMessage}", e)
          InternalServerError(Json.obj("error" -> "An internal error occurred."))
      }
    }

  /**
   * Reject a proposal.
   * POST /api/v1/discovery/proposals/:id/reject
   */
  def rejectProposal(id: Int): Action[RejectProposalRequest] =
    secureApi.jsonAction[RejectProposalRequest].async { request =>
      val body = request.body
      discoveryService.rejectProposal(id, ApiCuratorId, body.reason).map { proposal =>
        Ok(Json.toJson(proposal))
      }.recover {
        case e: NoSuchElementException =>
          NotFound(Json.obj("error" -> e.getMessage))
        case e: IllegalStateException =>
          BadRequest(Json.obj("error" -> e.getMessage))
        case e: Exception =>
          logger.error(s"Error rejecting proposal $id: ${e.getMessage}", e)
          InternalServerError(Json.obj("error" -> "An internal error occurred."))
      }
    }

  /**
   * Promote an accepted proposal to the canonical haplogroup tree.
   * POST /api/v1/discovery/proposals/:id/promote
   */
  def promoteProposal(id: Int): Action[AnyContent] =
    secureApi.async { request =>
      treeEvolutionService.promoteProposal(id, ApiCuratorId).map { result =>
        Ok(Json.toJson(result))
      }.recover {
        case e: NoSuchElementException =>
          NotFound(Json.obj("error" -> e.getMessage))
        case e: IllegalStateException =>
          BadRequest(Json.obj("error" -> e.getMessage))
        case e: Exception =>
          logger.error(s"Error promoting proposal $id: ${e.getMessage}", e)
          InternalServerError(Json.obj("error" -> "An internal error occurred."))
      }
    }

  /**
   * Get audit trail for a proposal.
   * GET /api/v1/discovery/proposals/:id/audit
   */
  def getAuditTrail(id: Int): Action[AnyContent] = secureApi.async { _ =>
    discoveryService.getAuditTrail(id).map { actions =>
      Ok(Json.toJson(actions))
    }.recover {
      case e: Exception =>
        logger.error(s"Error getting audit trail for proposal $id: ${e.getMessage}", e)
        InternalServerError(Json.obj("error" -> "An internal error occurred."))
    }
  }

  private def parseHaplogroupType(s: String): Option[HaplogroupType] = s.toUpperCase match {
    case "Y" => Some(HaplogroupType.Y)
    case "MT" => Some(HaplogroupType.MT)
    case _ => None
  }
}
