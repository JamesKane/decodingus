package controllers

import actions.{AuthenticatedAction, AuthenticatedRequest, PermissionAction}
import jakarta.inject.{Inject, Singleton}
import models.HaplogroupType
import models.dal.domain.haplogroups.{DeferPriority, ResolutionType, WipResolutionRow}
import models.domain.haplogroups.{ChangeSetStatus, ChangeStatus}
import org.webjars.play.WebJarsUtil
import play.api.Logging
import play.api.data.Form
import play.api.data.Forms.*
import play.api.i18n.I18nSupport
import play.api.libs.json.*
import play.api.mvc.*
import repositories.WipTreeRepository
import services.TreeVersioningService

import java.io.File
import java.time.LocalDateTime
import scala.io.Source
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Using

/**
 * Curator UI controller for Tree Versioning operations.
 * Provides HTML views for managing change sets from tree merge operations.
 */
@Singleton
class TreeVersioningCuratorController @Inject()(
    val controllerComponents: ControllerComponents,
    protected val authenticatedAction: AuthenticatedAction,
    protected val permissionAction: PermissionAction,
    treeVersioningService: TreeVersioningService,
    wipTreeRepository: WipTreeRepository
)(implicit ec: ExecutionContext, webJarsUtil: WebJarsUtil)
    extends BaseController with I18nSupport with Logging with BaseCuratorController {

  // Forms
  case class DiscardFormData(reason: String)
  private val discardForm: Form[DiscardFormData] = Form(
    mapping(
      "reason" -> nonEmptyText(minLength = 10, maxLength = 500)
    )(DiscardFormData.apply)(d => Some(d.reason))
  )

  case class ReviewChangeFormData(action: String, notes: Option[String])
  private val reviewChangeForm: Form[ReviewChangeFormData] = Form(
    mapping(
      "action" -> nonEmptyText.verifying("Invalid action", a => Seq("APPLIED", "SKIPPED", "REVERTED").contains(a)),
      "notes" -> optional(text(maxLength = 1000))
    )(ReviewChangeFormData.apply)(d => Some((d.action, d.notes)))
  )

  // Resolution form data classes
  case class ReparentFormData(
    wipHaplogroupId: Option[Int],
    wipReparentId: Option[Int],
    newParentId: Option[Int],
    newParentPlaceholderId: Option[Int],
    notes: Option[String]
  )
  private val reparentForm: Form[ReparentFormData] = Form(
    mapping(
      "wipHaplogroupId" -> optional(number),
      "wipReparentId" -> optional(number),
      "newParentId" -> optional(number),
      "newParentPlaceholderId" -> optional(number),
      "notes" -> optional(text(maxLength = 1000))
    )(ReparentFormData.apply)(d => Some((d.wipHaplogroupId, d.wipReparentId, d.newParentId, d.newParentPlaceholderId, d.notes)))
  )

  case class EditVariantsFormData(
    wipHaplogroupId: Option[Int],
    wipReparentId: Option[Int],
    variantsToAdd: String,
    variantsToRemove: String,
    notes: Option[String]
  )
  private val editVariantsForm: Form[EditVariantsFormData] = Form(
    mapping(
      "wipHaplogroupId" -> optional(number),
      "wipReparentId" -> optional(number),
      "variantsToAdd" -> text,
      "variantsToRemove" -> text,
      "notes" -> optional(text(maxLength = 1000))
    )(EditVariantsFormData.apply)(d => Some((d.wipHaplogroupId, d.wipReparentId, d.variantsToAdd, d.variantsToRemove, d.notes)))
  )

  case class MergeExistingFormData(
    wipHaplogroupId: Option[Int],
    wipReparentId: Option[Int],
    mergeTargetId: Int,
    notes: Option[String]
  )
  private val mergeExistingForm: Form[MergeExistingFormData] = Form(
    mapping(
      "wipHaplogroupId" -> optional(number),
      "wipReparentId" -> optional(number),
      "mergeTargetId" -> number,
      "notes" -> optional(text(maxLength = 1000))
    )(MergeExistingFormData.apply)(d => Some((d.wipHaplogroupId, d.wipReparentId, d.mergeTargetId, d.notes)))
  )

  case class DeferFormData(
    wipHaplogroupId: Option[Int],
    wipReparentId: Option[Int],
    priority: String,
    reason: String,
    notes: Option[String]
  )
  private val deferForm: Form[DeferFormData] = Form(
    mapping(
      "wipHaplogroupId" -> optional(number),
      "wipReparentId" -> optional(number),
      "priority" -> nonEmptyText.verifying("Invalid priority", p => Seq("LOW", "NORMAL", "HIGH", "CRITICAL").contains(p.toUpperCase)),
      "reason" -> nonEmptyText(minLength = 5, maxLength = 500),
      "notes" -> optional(text(maxLength = 1000))
    )(DeferFormData.apply)(d => Some((d.wipHaplogroupId, d.wipReparentId, d.priority, d.reason, d.notes)))
  )

  // ============================================================================
  // Change Set List
  // ============================================================================

  /**
   * Change set list page (wrapper with filters).
   */
  def listChangeSets(hgType: Option[String], status: Option[String], pageSize: Int): Action[AnyContent] =
    withPermission("tree.version.view") { implicit request =>
      Ok(views.html.curator.changesets.list(hgType, status, pageSize))
    }

  /**
   * Change set list fragment (loaded via HTMX).
   */
  def changeSetsFragment(hgType: Option[String], status: Option[String], page: Int, pageSize: Int): Action[AnyContent] =
    withPermission("tree.version.view").async { implicit request =>
      val haplogroupType = hgType.flatMap(parseHaplogroupType)
      val changeSetStatus = status.flatMap(parseChangeSetStatus)

      treeVersioningService.listChangeSets(haplogroupType, changeSetStatus, page, pageSize).map { case (summaries, total) =>
        val totalPages = Math.max(1, (total + pageSize - 1) / pageSize)
        Ok(views.html.curator.changesets.listFragment(summaries, hgType, status, page, totalPages, pageSize))
      }
    }

  // ============================================================================
  // Change Set Details
  // ============================================================================

  /**
   * Change set detail panel (loaded via HTMX).
   */
  def changeSetDetailPanel(id: Int): Action[AnyContent] =
    withPermission("tree.version.view").async { implicit request =>
      treeVersioningService.getChangeSetDetails(id).map {
        case Some(details) =>
          Ok(views.html.curator.changesets.detailPanel(details, discardForm))
        case None =>
          NotFound(views.html.fragments.errorPanel("Change set not found"))
      }
    }

  /**
   * Get pending changes for a change set (loaded via HTMX).
   */
  def pendingChangesFragment(id: Int, limit: Int): Action[AnyContent] =
    withPermission("tree.version.view").async { implicit request =>
      treeVersioningService.getPendingReviewChangesWithNames(id, limit).map { changes =>
        Ok(views.html.curator.changesets.changesFragment(id, changes, reviewChangeForm))
      }
    }

  // ============================================================================
  // Change Set Actions
  // ============================================================================

  /**
   * Start review of a change set.
   */
  def startReview(id: Int): Action[AnyContent] =
    withPermission("tree.version.review").async { implicit request =>
      treeVersioningService.startReview(id, curatorId(request)).map { success =>
        if (success) {
          Redirect(routes.TreeVersioningCuratorController.listChangeSets(None, None, 20))
            .flashing("success" -> s"Review started for change set #$id")
        } else {
          Redirect(routes.TreeVersioningCuratorController.listChangeSets(None, None, 20))
            .flashing("error" -> "Failed to start review")
        }
      }.recover {
        case e: IllegalStateException =>
          Redirect(routes.TreeVersioningCuratorController.listChangeSets(None, None, 20))
            .flashing("error" -> e.getMessage)
      }
    }

  /**
   * Apply a change set to Production.
   */
  def applyChangeSet(id: Int): Action[AnyContent] =
    withPermission("tree.version.promote").async { implicit request =>
      treeVersioningService.applyChangeSet(id, curatorId(request)).map { success =>
        if (success) {
          Redirect(routes.TreeVersioningCuratorController.listChangeSets(None, None, 20))
            .flashing("success" -> s"Change set #$id applied to Production!")
        } else {
          Redirect(routes.TreeVersioningCuratorController.listChangeSets(None, None, 20))
            .flashing("error" -> "Failed to apply change set")
        }
      }.recover {
        case e: IllegalStateException =>
          Redirect(routes.TreeVersioningCuratorController.listChangeSets(None, None, 20))
            .flashing("error" -> e.getMessage)
      }
    }

  /**
   * Discard a change set.
   */
  def discardChangeSet(id: Int): Action[AnyContent] =
    withPermission("tree.version.discard").async { implicit request =>
      discardForm.bindFromRequest().fold(
        formWithErrors => {
          Future.successful(
            Redirect(routes.TreeVersioningCuratorController.listChangeSets(None, None, 20))
              .flashing("error" -> "Please provide a reason for discarding (min 10 characters)")
          )
        },
        data => {
          treeVersioningService.discardChangeSet(id, curatorId(request), data.reason).map { success =>
            if (success) {
              Redirect(routes.TreeVersioningCuratorController.listChangeSets(None, None, 20))
                .flashing("success" -> s"Change set #$id discarded")
            } else {
              Redirect(routes.TreeVersioningCuratorController.listChangeSets(None, None, 20))
                .flashing("error" -> "Failed to discard change set")
            }
          }.recover {
            case e: IllegalStateException =>
              Redirect(routes.TreeVersioningCuratorController.listChangeSets(None, None, 20))
                .flashing("error" -> e.getMessage)
          }
        }
      )
    }

  /**
   * Approve all pending changes in a change set.
   */
  def approveAllPending(id: Int): Action[AnyContent] =
    withPermission("tree.version.review").async { implicit request =>
      treeVersioningService.approveAllPending(id, curatorId(request)).map { count =>
        Redirect(routes.TreeVersioningCuratorController.listChangeSets(None, None, 20))
          .flashing("success" -> s"Approved $count pending changes in change set #$id")
      }.recover {
        case e: Exception =>
          Redirect(routes.TreeVersioningCuratorController.listChangeSets(None, None, 20))
            .flashing("error" -> e.getMessage)
      }
    }

  // ============================================================================
  // Individual Change Review
  // ============================================================================

  /**
   * Review an individual change (HTMX POST).
   */
  def reviewChange(changeSetId: Int, changeId: Int): Action[AnyContent] =
    withPermission("tree.version.review").async { implicit request =>
      reviewChangeForm.bindFromRequest().fold(
        formWithErrors => {
          Future.successful(BadRequest(views.html.fragments.errorPanel("Invalid review action")))
        },
        data => {
          parseChangeStatus(data.action) match {
            case None =>
              Future.successful(BadRequest(views.html.fragments.errorPanel(s"Invalid action: ${data.action}")))
            case Some(status) =>
              treeVersioningService.reviewChange(changeId, curatorId(request), status, data.notes).map { success =>
                if (success) {
                  Ok(views.html.fragments.successPanel(s"Change #$changeId marked as ${data.action}"))
                } else {
                  BadRequest(views.html.fragments.errorPanel("Failed to review change"))
                }
              }.recover {
                case e: Exception =>
                  BadRequest(views.html.fragments.errorPanel(e.getMessage))
              }
          }
        }
      )
    }

  // ============================================================================
  // Ambiguity Report Views
  // ============================================================================

  /**
   * View ambiguity report for a change set.
   */
  def ambiguityReport(id: Int): Action[AnyContent] =
    withPermission("tree.version.view").async { implicit request =>
      treeVersioningService.getChangeSetDetails(id).map {
        case Some(details) =>
          details.changeSet.ambiguityReportPath match {
            case Some(path) =>
              val file = new File(path)
              if (file.exists()) {
                Using(Source.fromFile(file)) { source =>
                  val content = source.mkString
                  Ok(views.html.curator.changesets.ambiguityReport(details.changeSet, content))
                }.getOrElse {
                  InternalServerError(views.html.fragments.errorPanel("Failed to read ambiguity report"))
                }
              } else {
                NotFound(views.html.fragments.errorPanel(s"Ambiguity report not found at: ${file.getName}"))
              }
            case None =>
              Ok(views.html.curator.changesets.ambiguityReport(details.changeSet, "No ambiguities were detected during this merge."))
          }
        case None =>
          NotFound(views.html.fragments.errorPanel("Change set not found"))
      }
    }

  /**
   * Download ambiguity report as markdown file.
   */
  def downloadAmbiguityReport(id: Int): Action[AnyContent] =
    withPermission("tree.version.view").async { implicit request =>
      treeVersioningService.getChangeSetDetails(id).map {
        case Some(details) =>
          details.changeSet.ambiguityReportPath match {
            case Some(path) =>
              val file = new File(path)
              if (file.exists()) {
                Ok.sendFile(file, fileName = _ => Some(file.getName))
                  .as("text/markdown")
              } else {
                NotFound("Ambiguity report file not found")
              }
            case None =>
              NotFound("No ambiguity report available for this change set")
          }
        case None =>
          NotFound("Change set not found")
      }
    }

  // ============================================================================
  // Tree Diff Views
  // ============================================================================

  /**
   * Diff view page for a change set.
   */
  def diffView(id: Int): Action[AnyContent] =
    withPermission("tree.version.view").async { implicit request =>
      treeVersioningService.getChangeSetDetails(id).map {
        case Some(details) =>
          Ok(views.html.curator.changesets.diffView(details))
        case None =>
          NotFound(views.html.fragments.errorPanel("Change set not found"))
      }
    }

  /**
   * Diff fragment (loaded via HTMX).
   */
  def diffFragment(id: Int): Action[AnyContent] =
    withPermission("tree.version.view").async { implicit request =>
      treeVersioningService.getTreeDiff(id).map { diff =>
        Ok(views.html.curator.changesets.diffFragment(diff))
      }
    }

  /**
   * Get ASCII tree preview of proposed changes for a change set.
   * Returns plain text showing the tree structure with markers for new/reparented nodes.
   */
  def treePreview(id: Int): Action[AnyContent] =
    withPermission("tree.version.view").async { implicit request =>
      treeVersioningService.getTreePreview(id).map { preview =>
        Ok(preview).as("text/plain; charset=utf-8")
      }
    }

  // ============================================================================
  // Conflict Resolution API
  // ============================================================================

  /**
   * Get all resolutions for a change set.
   */
  def listResolutions(id: Int): Action[AnyContent] =
    withPermission("tree.version.view").async { implicit request =>
      wipTreeRepository.getResolutionsForChangeSet(id).map { resolutions =>
        Ok(Json.toJson(resolutions.map(resolutionToJson)))
      }
    }

  /**
   * Get deferred items for a change set.
   */
  def listDeferredItems(id: Int): Action[AnyContent] =
    withPermission("tree.version.view").async { implicit request =>
      wipTreeRepository.getDeferredItems(id).map { deferred =>
        Ok(Json.toJson(deferred.map(resolutionToJson)))
      }
    }

  /**
   * Create a REPARENT resolution - change the parent of a node.
   */
  def resolveReparent(id: Int): Action[AnyContent] =
    withPermission("tree.version.review").async { implicit request =>
      reparentForm.bindFromRequest().fold(
        formWithErrors => {
          Future.successful(BadRequest(Json.obj(
            "error" -> "Invalid form data",
            "details" -> formWithErrors.errors.map(e => s"${e.key}: ${e.message}").mkString(", ")
          )))
        },
        data => {
          if (data.wipHaplogroupId.isEmpty && data.wipReparentId.isEmpty) {
            Future.successful(BadRequest(Json.obj(
              "error" -> "Either wipHaplogroupId or wipReparentId must be provided"
            )))
          } else if (data.newParentId.isEmpty && data.newParentPlaceholderId.isEmpty) {
            Future.successful(BadRequest(Json.obj(
              "error" -> "Either newParentId or newParentPlaceholderId must be provided"
            )))
          } else {
            val resolution = WipResolutionRow(
              id = None,
              changeSetId = id,
              wipHaplogroupId = data.wipHaplogroupId,
              wipReparentId = data.wipReparentId,
              resolutionType = "REPARENT",
              newParentId = data.newParentId,
              newParentPlaceholderId = data.newParentPlaceholderId,
              mergeTargetId = None,
              variantsToAdd = None,
              variantsToRemove = None,
              deferReason = None,
              deferPriority = "NORMAL",
              curatorId = curatorId(request),
              curatorNotes = data.notes,
              status = "PENDING",
              createdAt = LocalDateTime.now(),
              appliedAt = None
            )
            wipTreeRepository.createResolution(resolution).map { resolutionId =>
              Created(Json.obj(
                "message" -> "Reparent resolution created",
                "resolutionId" -> resolutionId
              ))
            }
          }
        }
      )
    }

  /**
   * Create an EDIT_VARIANTS resolution - add or remove variant associations.
   */
  def resolveEditVariants(id: Int): Action[AnyContent] =
    withPermission("tree.version.review").async { implicit request =>
      editVariantsForm.bindFromRequest().fold(
        formWithErrors => {
          Future.successful(BadRequest(Json.obj(
            "error" -> "Invalid form data",
            "details" -> formWithErrors.errors.map(e => s"${e.key}: ${e.message}").mkString(", ")
          )))
        },
        data => {
          if (data.wipHaplogroupId.isEmpty && data.wipReparentId.isEmpty) {
            Future.successful(BadRequest(Json.obj(
              "error" -> "Either wipHaplogroupId or wipReparentId must be provided"
            )))
          } else {
            val resolution = WipResolutionRow(
              id = None,
              changeSetId = id,
              wipHaplogroupId = data.wipHaplogroupId,
              wipReparentId = data.wipReparentId,
              resolutionType = "EDIT_VARIANTS",
              newParentId = None,
              newParentPlaceholderId = None,
              mergeTargetId = None,
              variantsToAdd = Some(data.variantsToAdd),
              variantsToRemove = Some(data.variantsToRemove),
              deferReason = None,
              deferPriority = "NORMAL",
              curatorId = curatorId(request),
              curatorNotes = data.notes,
              status = "PENDING",
              createdAt = LocalDateTime.now(),
              appliedAt = None
            )
            wipTreeRepository.createResolution(resolution).map { resolutionId =>
              Created(Json.obj(
                "message" -> "Edit variants resolution created",
                "resolutionId" -> resolutionId
              ))
            }
          }
        }
      )
    }

  /**
   * Create a MERGE_EXISTING resolution - map WIP node to existing production node.
   */
  def resolveMergeExisting(id: Int): Action[AnyContent] =
    withPermission("tree.version.review").async { implicit request =>
      mergeExistingForm.bindFromRequest().fold(
        formWithErrors => {
          Future.successful(BadRequest(Json.obj(
            "error" -> "Invalid form data",
            "details" -> formWithErrors.errors.map(e => s"${e.key}: ${e.message}").mkString(", ")
          )))
        },
        data => {
          if (data.wipHaplogroupId.isEmpty && data.wipReparentId.isEmpty) {
            Future.successful(BadRequest(Json.obj(
              "error" -> "Either wipHaplogroupId or wipReparentId must be provided"
            )))
          } else {
            val resolution = WipResolutionRow(
              id = None,
              changeSetId = id,
              wipHaplogroupId = data.wipHaplogroupId,
              wipReparentId = data.wipReparentId,
              resolutionType = "MERGE_EXISTING",
              newParentId = None,
              newParentPlaceholderId = None,
              mergeTargetId = Some(data.mergeTargetId),
              variantsToAdd = None,
              variantsToRemove = None,
              deferReason = None,
              deferPriority = "NORMAL",
              curatorId = curatorId(request),
              curatorNotes = data.notes,
              status = "PENDING",
              createdAt = LocalDateTime.now(),
              appliedAt = None
            )
            wipTreeRepository.createResolution(resolution).map { resolutionId =>
              Created(Json.obj(
                "message" -> "Merge existing resolution created",
                "resolutionId" -> resolutionId
              ))
            }
          }
        }
      )
    }

  /**
   * Create a DEFER resolution - move to manual review queue.
   */
  def resolveDefer(id: Int): Action[AnyContent] =
    withPermission("tree.version.review").async { implicit request =>
      deferForm.bindFromRequest().fold(
        formWithErrors => {
          Future.successful(BadRequest(Json.obj(
            "error" -> "Invalid form data",
            "details" -> formWithErrors.errors.map(e => s"${e.key}: ${e.message}").mkString(", ")
          )))
        },
        data => {
          if (data.wipHaplogroupId.isEmpty && data.wipReparentId.isEmpty) {
            Future.successful(BadRequest(Json.obj(
              "error" -> "Either wipHaplogroupId or wipReparentId must be provided"
            )))
          } else {
            val resolution = WipResolutionRow(
              id = None,
              changeSetId = id,
              wipHaplogroupId = data.wipHaplogroupId,
              wipReparentId = data.wipReparentId,
              resolutionType = "DEFER",
              newParentId = None,
              newParentPlaceholderId = None,
              mergeTargetId = None,
              variantsToAdd = None,
              variantsToRemove = None,
              deferReason = Some(data.reason),
              deferPriority = data.priority.toUpperCase,
              curatorId = curatorId(request),
              curatorNotes = data.notes,
              status = "PENDING",
              createdAt = LocalDateTime.now(),
              appliedAt = None
            )
            wipTreeRepository.createResolution(resolution).map { resolutionId =>
              Created(Json.obj(
                "message" -> "Defer resolution created",
                "resolutionId" -> resolutionId
              ))
            }
          }
        }
      )
    }

  /**
   * Cancel a resolution.
   */
  def cancelResolution(changeSetId: Int, resolutionId: Int): Action[AnyContent] =
    withPermission("tree.version.review").async { implicit request =>
      wipTreeRepository.cancelResolution(resolutionId).map { updated =>
        if (updated > 0) {
          Ok(Json.obj(
            "message" -> "Resolution cancelled",
            "resolutionId" -> resolutionId
          ))
        } else {
          NotFound(Json.obj("error" -> "Resolution not found"))
        }
      }
    }

  // JSON serialization helper for WipResolutionRow
  private def resolutionToJson(r: WipResolutionRow): JsObject = Json.obj(
    "id" -> r.id,
    "changeSetId" -> r.changeSetId,
    "wipHaplogroupId" -> r.wipHaplogroupId,
    "wipReparentId" -> r.wipReparentId,
    "resolutionType" -> r.resolutionType,
    "newParentId" -> r.newParentId,
    "newParentPlaceholderId" -> r.newParentPlaceholderId,
    "mergeTargetId" -> r.mergeTargetId,
    "variantsToAdd" -> r.variantsToAdd,
    "variantsToRemove" -> r.variantsToRemove,
    "deferReason" -> r.deferReason,
    "deferPriority" -> r.deferPriority,
    "curatorId" -> r.curatorId,
    "curatorNotes" -> r.curatorNotes,
    "status" -> r.status,
    "createdAt" -> r.createdAt.toString,
    "appliedAt" -> r.appliedAt.map(_.toString)
  )

  // ============================================================================
  // Helpers
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
