package controllers

import actions.{AuthenticatedAction, AuthenticatedRequest, PermissionAction}
import jakarta.inject.{Inject, Singleton}
import models.HaplogroupType
import models.domain.haplogroups.{ChangeSetStatus, ChangeStatus}
import org.webjars.play.WebJarsUtil
import play.api.Logging
import play.api.data.Form
import play.api.data.Forms.*
import play.api.i18n.I18nSupport
import play.api.mvc.*
import services.TreeVersioningService

import java.io.File
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
    treeVersioningService: TreeVersioningService
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
