package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Singleton}
import models.api.haplogroups.*
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{Action, BaseController, ControllerComponents}
import services.HaplogroupTreeMergeService

import scala.concurrent.{ExecutionContext, Future}

/**
 * API controller for haplogroup tree merge operations.
 * Secured with X-API-Key authentication.
 *
 * Endpoints:
 * - POST /api/v1/manage/haplogroups/merge         - Full tree merge
 * - POST /api/v1/manage/haplogroups/merge/subtree - Subtree merge under anchor
 * - POST /api/v1/manage/haplogroups/merge/preview - Preview merge without changes
 */
@Singleton
class HaplogroupTreeMergeController @Inject()(
  val controllerComponents: ControllerComponents,
  secureApi: ApiSecurityAction,
  mergeService: HaplogroupTreeMergeService
)(implicit ec: ExecutionContext) extends BaseController {

  private val logger = Logger(this.getClass)

  /**
   * Merge a full haplogroup tree, replacing the existing tree for the given type.
   *
   * Request body: TreeMergeRequest
   * - haplogroupType: "Y" or "MT"
   * - sourceTree: Nested PhyloNodeInput tree structure
   * - sourceName: Attribution source (e.g., "ytree.net", "ISOGG")
   * - priorityConfig: Optional source priority ordering
   * - conflictStrategy: Optional conflict resolution strategy
   * - dryRun: If true, simulates merge without applying changes
   */
  def mergeFullTree(): Action[TreeMergeRequest] =
    secureApi.jsonAction[TreeMergeRequest].async { request =>
      logger.info(s"API: Full tree merge for ${request.body.haplogroupType} from ${request.body.sourceName}" +
        (if (request.body.dryRun) " (dry run)" else ""))

      // Initiate the merge in the background
      mergeService.mergeFullTree(request.body).onComplete {
        case scala.util.Success(response) =>
          logger.info(s"Background full tree merge completed for ${request.body.haplogroupType} with success: ${response.success}. Stats: ${response.statistics}")
          if (!response.success) {
            logger.error(s"Background full tree merge completed with errors for ${request.body.haplogroupType}: ${response.errors}")
          }
        case scala.util.Failure(e) =>
          logger.error(s"Background full tree merge failed for ${request.body.haplogroupType}: ${e.getMessage}", e)
      }(ec) // Ensure onComplete runs on the correct execution context

      // Immediately return an Accepted response
      Future.successful(Accepted(Json.obj(
        "status" -> "Processing",
        "message" -> s"Full tree merge for ${request.body.haplogroupType} initiated and is running in the background. Check logs for details."
      )))
    }

  /**
   * Merge a subtree under a specific anchor haplogroup.
   *
   * Request body: SubtreeMergeRequest
   * - haplogroupType: "Y" or "MT"
   * - anchorHaplogroupName: Name of the haplogroup to merge under
   * - sourceTree: Nested PhyloNodeInput tree structure
   * - sourceName: Attribution source
   * - priorityConfig: Optional source priority ordering
   * - conflictStrategy: Optional conflict resolution strategy
   * - dryRun: If true, simulates merge without applying changes
   */
  def mergeSubtree(): Action[SubtreeMergeRequest] =
    secureApi.jsonAction[SubtreeMergeRequest].async { request =>
      logger.info(s"API: Subtree merge under ${request.body.anchorHaplogroupName} " +
        s"for ${request.body.haplogroupType} from ${request.body.sourceName}" +
        (if (request.body.dryRun) " (dry run)" else ""))

      // Initiate the merge in the background
      mergeService.mergeSubtree(request.body).onComplete {
        case scala.util.Success(response) =>
          logger.info(s"Background subtree merge completed for ${request.body.haplogroupType} under ${request.body.anchorHaplogroupName} with success: ${response.success}. Stats: ${response.statistics}")
          if (!response.success) {
            logger.error(s"Background subtree merge completed with errors for ${request.body.haplogroupType} under ${request.body.anchorHaplogroupName}: ${response.errors}")
          }
        case scala.util.Failure(e) =>
          logger.error(s"Background subtree merge failed for ${request.body.haplogroupType} under ${request.body.anchorHaplogroupName}: ${e.getMessage}", e)
      }(ec) // Ensure onComplete runs on the correct execution context

      // Immediately return an Accepted response
      Future.successful(Accepted(Json.obj(
        "status" -> "Processing",
        "message" -> s"Subtree merge for ${request.body.haplogroupType} under ${request.body.anchorHaplogroupName} initiated and is running in the background. Check logs for details."
      )))
    }

  /**
   * Preview a merge operation without applying changes.
   *
   * Request body: MergePreviewRequest
   * - haplogroupType: "Y" or "MT"
   * - anchorHaplogroupName: Optional anchor for subtree preview
   * - sourceTree: Nested PhyloNodeInput tree structure
   * - sourceName: Attribution source
   * - priorityConfig: Optional source priority ordering
   */
  def previewMerge(): Action[MergePreviewRequest] =
    secureApi.jsonAction[MergePreviewRequest].async { request =>
      logger.info(s"API: Preview merge for ${request.body.haplogroupType} from ${request.body.sourceName}" +
        request.body.anchorHaplogroupName.map(a => s" under $a").getOrElse(""))

      mergeService.previewMerge(request.body).map { response =>
        Ok(Json.toJson(response))
      }.recover { case e: Exception =>
        logger.error(s"Merge preview failed: ${e.getMessage}", e)
        InternalServerError(Json.obj(
          "error" -> "Preview operation failed",
          "details" -> e.getMessage
        ))
      }
    }
}
