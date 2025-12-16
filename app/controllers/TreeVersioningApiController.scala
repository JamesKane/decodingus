package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Singleton}
import models.HaplogroupType
import models.domain.haplogroups.{ChangeSetStatus, ChangeStatus}
import play.api.Logger
import play.api.libs.json.{Format, JsError, JsSuccess, Json, OFormat, Reads}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import services.TreeVersioningService

import scala.concurrent.{ExecutionContext, Future}

/**
 * API controller for Tree Versioning operations.
 * Provides endpoints for managing change sets from tree merge operations.
 * Secured with X-API-Key authentication.
 */
@Singleton
class TreeVersioningApiController @Inject()(
    val controllerComponents: ControllerComponents,
    secureApi: ApiSecurityAction,
    treeVersioningService: TreeVersioningService
)(implicit ec: ExecutionContext) extends BaseController {

  private val logger = Logger(this.getClass)

  // ============================================================================
  // Request/Response DTOs
  // ============================================================================

  case class StartReviewRequest(curatorId: String)
  object StartReviewRequest {
    implicit val format: OFormat[StartReviewRequest] = Json.format[StartReviewRequest]
  }

  case class ApplyChangeSetRequest(curatorId: String)
  object ApplyChangeSetRequest {
    implicit val format: OFormat[ApplyChangeSetRequest] = Json.format[ApplyChangeSetRequest]
  }

  case class DiscardChangeSetRequest(curatorId: String, reason: String)
  object DiscardChangeSetRequest {
    implicit val format: OFormat[DiscardChangeSetRequest] = Json.format[DiscardChangeSetRequest]
  }

  case class ReviewChangeRequest(
    curatorId: String,
    action: String, // "APPLIED", "SKIPPED", "REVERTED"
    notes: Option[String] = None
  )
  object ReviewChangeRequest {
    implicit val format: OFormat[ReviewChangeRequest] = Json.format[ReviewChangeRequest]
  }

  case class ApproveAllRequest(curatorId: String)
  object ApproveAllRequest {
    implicit val format: OFormat[ApproveAllRequest] = Json.format[ApproveAllRequest]
  }

  case class AddCommentRequest(author: String, content: String, treeChangeId: Option[Int] = None)
  object AddCommentRequest {
    implicit val format: OFormat[AddCommentRequest] = Json.format[AddCommentRequest]
  }

  // ============================================================================
  // Change Set Endpoints
  // ============================================================================

  /**
   * List change sets with optional filters.
   * GET /api/v1/manage/change-sets?haplogroupType=Y&status=READY_FOR_REVIEW&page=1&pageSize=20
   */
  def listChangeSets(
    haplogroupType: Option[String],
    status: Option[String],
    page: Int,
    pageSize: Int
  ): Action[AnyContent] = secureApi.async { _ =>
    val hgType = haplogroupType.flatMap(parseHaplogroupType)
    val csStatus = status.flatMap(parseChangeSetStatus)

    treeVersioningService.listChangeSets(hgType, csStatus, page, pageSize).map { case (summaries, total) =>
      Ok(Json.obj(
        "changeSets" -> summaries,
        "total" -> total,
        "page" -> page,
        "pageSize" -> pageSize,
        "totalPages" -> ((total + pageSize - 1) / pageSize)
      ))
    }.recover {
      case e: Exception =>
        logger.error(s"Error listing change sets: ${e.getMessage}", e)
        InternalServerError(Json.obj("error" -> e.getMessage))
    }
  }

  /**
   * Get change set details.
   * GET /api/v1/manage/change-sets/:id
   */
  def getChangeSetDetails(id: Int): Action[AnyContent] = secureApi.async { _ =>
    treeVersioningService.getChangeSetDetails(id).map {
      case Some(details) => Ok(Json.toJson(details))
      case None => NotFound(Json.obj("error" -> s"Change set $id not found"))
    }.recover {
      case e: Exception =>
        logger.error(s"Error getting change set $id: ${e.getMessage}", e)
        InternalServerError(Json.obj("error" -> e.getMessage))
    }
  }

  /**
   * Start review of a change set.
   * POST /api/v1/manage/change-sets/:id/start-review
   */
  def startReview(id: Int): Action[StartReviewRequest] =
    secureApi.jsonAction[StartReviewRequest].async { request =>
      treeVersioningService.startReview(id, request.body.curatorId).map { success =>
        if (success) {
          Ok(Json.obj("success" -> true, "message" -> s"Review started for change set $id"))
        } else {
          BadRequest(Json.obj("success" -> false, "message" -> "Failed to start review"))
        }
      }.recover {
        case e: IllegalStateException =>
          BadRequest(Json.obj("error" -> e.getMessage))
        case e: NoSuchElementException =>
          NotFound(Json.obj("error" -> e.getMessage))
        case e: Exception =>
          logger.error(s"Error starting review for change set $id: ${e.getMessage}", e)
          InternalServerError(Json.obj("error" -> e.getMessage))
      }
    }

  /**
   * Apply a change set to Production.
   * POST /api/v1/manage/change-sets/:id/apply
   */
  def applyChangeSet(id: Int): Action[ApplyChangeSetRequest] =
    secureApi.jsonAction[ApplyChangeSetRequest].async { request =>
      treeVersioningService.applyChangeSet(id, request.body.curatorId).map { success =>
        if (success) {
          Ok(Json.obj("success" -> true, "message" -> s"Change set $id applied to Production"))
        } else {
          BadRequest(Json.obj("success" -> false, "message" -> "Failed to apply change set"))
        }
      }.recover {
        case e: IllegalStateException =>
          BadRequest(Json.obj("error" -> e.getMessage))
        case e: NoSuchElementException =>
          NotFound(Json.obj("error" -> e.getMessage))
        case e: Exception =>
          logger.error(s"Error applying change set $id: ${e.getMessage}", e)
          InternalServerError(Json.obj("error" -> e.getMessage))
      }
    }

  /**
   * Discard a change set.
   * POST /api/v1/manage/change-sets/:id/discard
   */
  def discardChangeSet(id: Int): Action[DiscardChangeSetRequest] =
    secureApi.jsonAction[DiscardChangeSetRequest].async { request =>
      val req = request.body
      treeVersioningService.discardChangeSet(id, req.curatorId, req.reason).map { success =>
        if (success) {
          Ok(Json.obj("success" -> true, "message" -> s"Change set $id discarded"))
        } else {
          BadRequest(Json.obj("success" -> false, "message" -> "Failed to discard change set"))
        }
      }.recover {
        case e: IllegalStateException =>
          BadRequest(Json.obj("error" -> e.getMessage))
        case e: NoSuchElementException =>
          NotFound(Json.obj("error" -> e.getMessage))
        case e: Exception =>
          logger.error(s"Error discarding change set $id: ${e.getMessage}", e)
          InternalServerError(Json.obj("error" -> e.getMessage))
      }
    }

  // ============================================================================
  // Change Review Endpoints
  // ============================================================================

  /**
   * Get pending changes for review.
   * GET /api/v1/manage/change-sets/:id/changes/pending?limit=50
   */
  def getPendingChanges(id: Int, limit: Int): Action[AnyContent] = secureApi.async { _ =>
    treeVersioningService.getPendingReviewChanges(id, limit).map { changes =>
      Ok(Json.obj(
        "changeSetId" -> id,
        "pendingChanges" -> changes,
        "count" -> changes.size
      ))
    }.recover {
      case e: Exception =>
        logger.error(s"Error getting pending changes for set $id: ${e.getMessage}", e)
        InternalServerError(Json.obj("error" -> e.getMessage))
    }
  }

  /**
   * Review an individual change.
   * POST /api/v1/manage/change-sets/:changeSetId/changes/:changeId/review
   */
  def reviewChange(changeSetId: Int, changeId: Int): Action[ReviewChangeRequest] =
    secureApi.jsonAction[ReviewChangeRequest].async { request =>
      val req = request.body
      parseChangeStatus(req.action) match {
        case None =>
          Future.successful(BadRequest(Json.obj("error" -> s"Invalid action: ${req.action}")))
        case Some(action) =>
          treeVersioningService.reviewChange(changeId, req.curatorId, action, req.notes).map { success =>
            if (success) {
              Ok(Json.obj("success" -> true, "message" -> s"Change $changeId reviewed as ${req.action}"))
            } else {
              BadRequest(Json.obj("success" -> false, "message" -> "Failed to review change"))
            }
          }.recover {
            case e: IllegalArgumentException =>
              BadRequest(Json.obj("error" -> e.getMessage))
            case e: Exception =>
              logger.error(s"Error reviewing change $changeId: ${e.getMessage}", e)
              InternalServerError(Json.obj("error" -> e.getMessage))
          }
      }
    }

  /**
   * Approve all pending changes in a change set.
   * POST /api/v1/manage/change-sets/:id/approve-all
   */
  def approveAllPending(id: Int): Action[ApproveAllRequest] =
    secureApi.jsonAction[ApproveAllRequest].async { request =>
      treeVersioningService.approveAllPending(id, request.body.curatorId).map { count =>
        Ok(Json.obj("success" -> true, "approvedCount" -> count))
      }.recover {
        case e: Exception =>
          logger.error(s"Error approving all changes in set $id: ${e.getMessage}", e)
          InternalServerError(Json.obj("error" -> e.getMessage))
      }
    }

  // ============================================================================
  // Comment Endpoints
  // ============================================================================

  /**
   * Add a comment to a change set.
   * POST /api/v1/manage/change-sets/:id/comments
   */
  def addComment(id: Int): Action[AddCommentRequest] =
    secureApi.jsonAction[AddCommentRequest].async { request =>
      val req = request.body
      treeVersioningService.addComment(id, req.author, req.content, req.treeChangeId).map { commentId =>
        Created(Json.obj("success" -> true, "commentId" -> commentId))
      }.recover {
        case e: Exception =>
          logger.error(s"Error adding comment to set $id: ${e.getMessage}", e)
          InternalServerError(Json.obj("error" -> e.getMessage))
      }
    }

  /**
   * List comments for a change set.
   * GET /api/v1/manage/change-sets/:id/comments
   */
  def listComments(id: Int): Action[AnyContent] = secureApi.async { _ =>
    treeVersioningService.listComments(id).map { comments =>
      Ok(Json.obj("changeSetId" -> id, "comments" -> comments))
    }.recover {
      case e: Exception =>
        logger.error(s"Error listing comments for set $id: ${e.getMessage}", e)
        InternalServerError(Json.obj("error" -> e.getMessage))
    }
  }

  // ============================================================================
  // Helper Methods
  // ============================================================================

  private def parseHaplogroupType(s: String): Option[HaplogroupType] = {
    try {
      Some(HaplogroupType.valueOf(s.toUpperCase))
    } catch {
      case _: IllegalArgumentException => None
    }
  }

  private def parseChangeSetStatus(s: String): Option[ChangeSetStatus] = {
    try {
      Some(ChangeSetStatus.fromString(s))
    } catch {
      case _: IllegalArgumentException => None
    }
  }

  private def parseChangeStatus(s: String): Option[ChangeStatus] = {
    try {
      Some(ChangeStatus.fromString(s))
    } catch {
      case _: IllegalArgumentException => None
    }
  }
}
