package controllers

import actions.{ApiSecurityAction, PdsAuthAction}
import jakarta.inject.{Inject, Singleton}
import play.api.Logging
import play.api.libs.json.{Json, OFormat}
import play.api.mvc.*
import services.{HeartbeatRequest, PdsFleetService, SubmissionProvenanceService}

import java.util.UUID
import scala.concurrent.ExecutionContext

@Singleton
class PdsFleetApiController @Inject()(
                                       val controllerComponents: ControllerComponents,
                                       pdsAuth: PdsAuthAction,
                                       secureApi: ApiSecurityAction,
                                       fleetService: PdsFleetService,
                                       submissionService: SubmissionProvenanceService
                                     )(implicit ec: ExecutionContext) extends BaseController with Logging {

  // --- PDS-authenticated endpoints (called by edge nodes) ---

  case class HeartbeatPayload(
                               status: String,
                               softwareVersion: Option[String] = None,
                               loadMetrics: Option[play.api.libs.json.JsValue] = None,
                               processingQueueSize: Option[Int] = None,
                               errorMessage: Option[String] = None,
                               lastCommitCid: Option[String] = None,
                               lastCommitRev: Option[String] = None
                             )
  object HeartbeatPayload { implicit val format: OFormat[HeartbeatPayload] = Json.format }

  def heartbeat(): Action[HeartbeatPayload] = pdsAuth.jsonAction[HeartbeatPayload].async { request =>
    val node = request.pdsNode
    val payload = request.body
    val hbRequest = HeartbeatRequest(
      did = node.did,
      pdsUrl = node.pdsUrl,
      handle = node.handle,
      nodeName = node.nodeName,
      softwareVersion = payload.softwareVersion,
      status = payload.status,
      loadMetrics = payload.loadMetrics,
      processingQueueSize = payload.processingQueueSize,
      lastCommitCid = payload.lastCommitCid,
      lastCommitRev = payload.lastCommitRev,
      errorMessage = payload.errorMessage
    )

    fleetService.processHeartbeat(hbRequest).map {
      case Right(updatedNode) => Ok(Json.toJson(updatedNode))
      case Left(error) => BadRequest(Json.obj("error" -> error))
    }
  }

  case class SubmissionPayload(
                                submissionType: String,
                                proposedValue: String,
                                biosampleId: Option[Int] = None,
                                biosampleGuid: Option[UUID] = None,
                                confidenceScore: Option[Double] = None,
                                algorithmVersion: Option[String] = None,
                                softwareVersion: Option[String] = None,
                                payload: Option[play.api.libs.json.JsValue] = None,
                                atUri: Option[String] = None,
                                atCid: Option[String] = None
                              )
  object SubmissionPayload { implicit val format: OFormat[SubmissionPayload] = Json.format }

  def submitData(): Action[SubmissionPayload] = pdsAuth.jsonAction[SubmissionPayload].async { request =>
    val node = request.pdsNode
    val p = request.body

    submissionService.recordSubmission(
      did = node.did,
      submissionType = p.submissionType,
      proposedValue = p.proposedValue,
      biosampleId = p.biosampleId,
      biosampleGuid = p.biosampleGuid,
      confidenceScore = p.confidenceScore,
      algorithmVersion = p.algorithmVersion,
      softwareVersion = p.softwareVersion,
      payload = p.payload,
      atUri = p.atUri,
      atCid = p.atCid
    ).map {
      case Right(submission) => Created(Json.toJson(submission))
      case Left(error) => BadRequest(Json.obj("error" -> error))
    }
  }

  // --- Admin-authenticated endpoints (X-API-Key secured) ---

  def getFleetSummary: Action[AnyContent] = secureApi.async { _ =>
    fleetService.getFleetSummary.map(summary => Ok(Json.toJson(summary)))
  }

  def listNodes(status: Option[String]): Action[AnyContent] = secureApi.async { _ =>
    fleetService.listNodes(status).map { nodes =>
      Ok(Json.obj("nodes" -> nodes, "total" -> nodes.size))
    }
  }

  def getNode(did: String): Action[AnyContent] = secureApi.async { _ =>
    fleetService.getNode(did).map {
      case Some(node) => Ok(Json.toJson(node))
      case None => NotFound(Json.obj("error" -> s"Node not found: $did"))
    }
  }

  def removeNode(did: String): Action[AnyContent] = secureApi.async { _ =>
    fleetService.removeNode(did).map {
      case Right(_) => Ok(Json.obj("removed" -> true))
      case Left(error) => NotFound(Json.obj("error" -> error))
    }
  }

  def markStaleOffline(): Action[AnyContent] = secureApi.async { _ =>
    fleetService.markStaleNodesOffline().map { count =>
      Ok(Json.obj("markedOffline" -> count))
    }
  }

  def getPendingSubmissions(submissionType: Option[String], limit: Int): Action[AnyContent] = secureApi.async { _ =>
    submissionService.getPendingSubmissions(submissionType, limit).map { submissions =>
      Ok(Json.obj("submissions" -> submissions, "total" -> submissions.size))
    }
  }

  case class ReviewRequest(reviewedBy: String, notes: Option[String] = None)
  object ReviewRequest { implicit val format: OFormat[ReviewRequest] = Json.format }

  def acceptSubmission(id: Int): Action[ReviewRequest] = secureApi.jsonAction[ReviewRequest].async { request =>
    submissionService.acceptSubmission(id, request.body.reviewedBy, request.body.notes).map {
      case Right(_) => Ok(Json.obj("accepted" -> true))
      case Left(error) => BadRequest(Json.obj("error" -> error))
    }
  }

  def rejectSubmission(id: Int): Action[ReviewRequest] = secureApi.jsonAction[ReviewRequest].async { request =>
    submissionService.rejectSubmission(id, request.body.reviewedBy, request.body.notes).map {
      case Right(_) => Ok(Json.obj("rejected" -> true))
      case Left(error) => BadRequest(Json.obj("error" -> error))
    }
  }

  def getNodeSubmissionSummary(did: String): Action[AnyContent] = secureApi.async { _ =>
    submissionService.getNodeSubmissionSummary(did).map {
      case Right(summary) => Ok(Json.toJson(summary))
      case Left(error) => NotFound(Json.obj("error" -> error))
    }
  }
}
