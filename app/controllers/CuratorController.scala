package controllers

import actions.{AuthenticatedAction, AuthenticatedRequest, PermissionAction}
import jakarta.inject.{Inject, Singleton}
import models.HaplogroupType
import models.dal.domain.genomics.Variant
import models.domain.haplogroups.Haplogroup
import org.webjars.play.WebJarsUtil
import play.api.Logging
import play.api.data.Form
import play.api.data.Forms.*
import play.api.i18n.I18nSupport
import play.api.mvc.*
import repositories.{HaplogroupCoreRepository, VariantRepository}
import services.CuratorAuditService

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

case class HaplogroupFormData(
    name: String,
    lineage: Option[String],
    description: Option[String],
    haplogroupType: String,
    source: String,
    confidenceLevel: String
)

case class VariantFormData(
    genbankContigId: Int,
    position: Int,
    referenceAllele: String,
    alternateAllele: String,
    variantType: String,
    rsId: Option[String],
    commonName: Option[String]
)

@Singleton
class CuratorController @Inject()(
    val controllerComponents: ControllerComponents,
    authenticatedAction: AuthenticatedAction,
    permissionAction: PermissionAction,
    haplogroupRepository: HaplogroupCoreRepository,
    variantRepository: VariantRepository,
    auditService: CuratorAuditService
)(implicit ec: ExecutionContext, webJarsUtil: WebJarsUtil)
    extends BaseController with I18nSupport with Logging {

  // Permission-based action composition
  private def withPermission(permission: String) =
    authenticatedAction andThen permissionAction(permission)

  // Forms
  private val haplogroupForm: Form[HaplogroupFormData] = Form(
    mapping(
      "name" -> nonEmptyText(1, 100),
      "lineage" -> optional(text(maxLength = 500)),
      "description" -> optional(text(maxLength = 2000)),
      "haplogroupType" -> nonEmptyText.verifying("Invalid type", t => HaplogroupType.fromString(t).isDefined),
      "source" -> nonEmptyText(1, 100),
      "confidenceLevel" -> nonEmptyText(1, 50)
    )(HaplogroupFormData.apply)(h => Some((h.name, h.lineage, h.description, h.haplogroupType, h.source, h.confidenceLevel)))
  )

  private val variantForm: Form[VariantFormData] = Form(
    mapping(
      "genbankContigId" -> number,
      "position" -> number,
      "referenceAllele" -> nonEmptyText(1, 1000),
      "alternateAllele" -> nonEmptyText(1, 1000),
      "variantType" -> nonEmptyText(1, 50),
      "rsId" -> optional(text(maxLength = 50)),
      "commonName" -> optional(text(maxLength = 100))
    )(VariantFormData.apply)(v => Some((v.genbankContigId, v.position, v.referenceAllele, v.alternateAllele, v.variantType, v.rsId, v.commonName)))
  )

  // === Dashboard ===

  def dashboard: Action[AnyContent] = withPermission("haplogroup.view").async { implicit request =>
    for {
      yCount <- haplogroupRepository.countByType(HaplogroupType.Y)
      mtCount <- haplogroupRepository.countByType(HaplogroupType.MT)
      variantCount <- variantRepository.count(None)
    } yield {
      Ok(views.html.curator.dashboard(yCount, mtCount, variantCount))
    }
  }

  // === Haplogroups ===

  def listHaplogroups(query: Option[String], hgType: Option[String], page: Int, pageSize: Int): Action[AnyContent] =
    withPermission("haplogroup.view").async { implicit request =>
      val haplogroupType = hgType.flatMap(HaplogroupType.fromString)
      val offset = (page - 1) * pageSize

      for {
        haplogroups <- query match {
          case Some(q) if q.nonEmpty => haplogroupRepository.search(q, haplogroupType, pageSize, offset)
          case _ => haplogroupRepository.search("", haplogroupType, pageSize, offset)
        }
        totalCount <- haplogroupRepository.count(query.filter(_.nonEmpty), haplogroupType)
      } yield {
        val totalPages = Math.max(1, (totalCount + pageSize - 1) / pageSize)
        Ok(views.html.curator.haplogroups.list(haplogroups, query, hgType, page, totalPages, pageSize))
      }
    }

  def haplogroupsFragment(query: Option[String], hgType: Option[String], page: Int, pageSize: Int): Action[AnyContent] =
    withPermission("haplogroup.view").async { implicit request =>
      val haplogroupType = hgType.flatMap(HaplogroupType.fromString)
      val offset = (page - 1) * pageSize

      for {
        haplogroups <- query match {
          case Some(q) if q.nonEmpty => haplogroupRepository.search(q, haplogroupType, pageSize, offset)
          case _ => haplogroupRepository.search("", haplogroupType, pageSize, offset)
        }
        totalCount <- haplogroupRepository.count(query.filter(_.nonEmpty), haplogroupType)
      } yield {
        val totalPages = Math.max(1, (totalCount + pageSize - 1) / pageSize)
        Ok(views.html.curator.haplogroups.listFragment(haplogroups, query, hgType, page, totalPages, pageSize))
      }
    }

  def haplogroupDetailPanel(id: Int): Action[AnyContent] =
    withPermission("haplogroup.view").async { implicit request =>
      for {
        haplogroupOpt <- haplogroupRepository.findById(id)
        parentOpt <- haplogroupRepository.getParent(id)
        children <- haplogroupRepository.getDirectChildren(id)
        history <- auditService.getHaplogroupHistory(id)
      } yield {
        haplogroupOpt match {
          case Some(haplogroup) =>
            Ok(views.html.curator.haplogroups.detailPanel(haplogroup, parentOpt, children, history))
          case None =>
            NotFound("Haplogroup not found")
        }
      }
    }

  def createHaplogroupForm: Action[AnyContent] =
    withPermission("haplogroup.create").async { implicit request =>
      Future.successful(Ok(views.html.curator.haplogroups.createForm(haplogroupForm)))
    }

  def createHaplogroup: Action[AnyContent] =
    withPermission("haplogroup.create").async { implicit request =>
      haplogroupForm.bindFromRequest().fold(
        formWithErrors => {
          Future.successful(BadRequest(views.html.curator.haplogroups.createForm(formWithErrors)))
        },
        data => {
          val haplogroup = Haplogroup(
            id = None,
            name = data.name,
            lineage = data.lineage,
            description = data.description,
            haplogroupType = HaplogroupType.fromString(data.haplogroupType).get,
            revisionId = 1,
            source = data.source,
            confidenceLevel = data.confidenceLevel,
            validFrom = LocalDateTime.now(),
            validUntil = None
          )

          for {
            newId <- haplogroupRepository.create(haplogroup)
            createdHaplogroup = haplogroup.copy(id = Some(newId))
            _ <- auditService.logHaplogroupCreate(request.user.id.get, createdHaplogroup, Some("Created via curator interface"))
          } yield {
            Redirect(routes.CuratorController.listHaplogroups(None, None, 1, 20))
              .flashing("success" -> s"Haplogroup '${data.name}' created successfully")
          }
        }
      )
    }

  def editHaplogroupForm(id: Int): Action[AnyContent] =
    withPermission("haplogroup.update").async { implicit request =>
      haplogroupRepository.findById(id).map {
        case Some(haplogroup) =>
          val formData = HaplogroupFormData(
            name = haplogroup.name,
            lineage = haplogroup.lineage,
            description = haplogroup.description,
            haplogroupType = haplogroup.haplogroupType.toString,
            source = haplogroup.source,
            confidenceLevel = haplogroup.confidenceLevel
          )
          Ok(views.html.curator.haplogroups.editForm(id, haplogroupForm.fill(formData)))
        case None =>
          NotFound("Haplogroup not found")
      }
    }

  def updateHaplogroup(id: Int): Action[AnyContent] =
    withPermission("haplogroup.update").async { implicit request =>
      haplogroupRepository.findById(id).flatMap {
        case Some(oldHaplogroup) =>
          haplogroupForm.bindFromRequest().fold(
            formWithErrors => {
              Future.successful(BadRequest(views.html.curator.haplogroups.editForm(id, formWithErrors)))
            },
            data => {
              val updatedHaplogroup = oldHaplogroup.copy(
                name = data.name,
                lineage = data.lineage,
                description = data.description,
                source = data.source,
                confidenceLevel = data.confidenceLevel
              )

              for {
                updated <- haplogroupRepository.update(updatedHaplogroup)
                _ <- if (updated) {
                  auditService.logHaplogroupUpdate(request.user.id.get, oldHaplogroup, updatedHaplogroup, Some("Updated via curator interface"))
                } else {
                  Future.successful(())
                }
              } yield {
                if (updated) {
                  Redirect(routes.CuratorController.listHaplogroups(None, None, 1, 20))
                    .flashing("success" -> s"Haplogroup '${data.name}' updated successfully")
                } else {
                  BadRequest("Failed to update haplogroup")
                }
              }
            }
          )
        case None =>
          Future.successful(NotFound("Haplogroup not found"))
      }
    }

  def deleteHaplogroup(id: Int): Action[AnyContent] =
    withPermission("haplogroup.delete").async { implicit request =>
      haplogroupRepository.findById(id).flatMap {
        case Some(haplogroup) =>
          for {
            deleted <- haplogroupRepository.softDelete(id, "curator-deletion")
            _ <- if (deleted) {
              auditService.logHaplogroupDelete(request.user.id.get, haplogroup, Some("Soft-deleted via curator interface"))
            } else {
              Future.successful(())
            }
          } yield {
            if (deleted) {
              Ok("Deleted").withHeaders("HX-Trigger" -> "haplogroupDeleted")
            } else {
              BadRequest("Failed to delete haplogroup")
            }
          }
        case None =>
          Future.successful(NotFound("Haplogroup not found"))
      }
    }

  // === Variants ===

  def listVariants(query: Option[String], page: Int, pageSize: Int): Action[AnyContent] =
    withPermission("variant.view").async { implicit request =>
      val offset = (page - 1) * pageSize

      for {
        variants <- query match {
          case Some(q) if q.nonEmpty => variantRepository.search(q, pageSize, offset)
          case _ => variantRepository.search("", pageSize, offset)
        }
        totalCount <- variantRepository.count(query.filter(_.nonEmpty))
      } yield {
        val totalPages = Math.max(1, (totalCount + pageSize - 1) / pageSize)
        Ok(views.html.curator.variants.list(variants, query, page, totalPages, pageSize))
      }
    }

  def variantsFragment(query: Option[String], page: Int, pageSize: Int): Action[AnyContent] =
    withPermission("variant.view").async { implicit request =>
      val offset = (page - 1) * pageSize

      for {
        variants <- query match {
          case Some(q) if q.nonEmpty => variantRepository.search(q, pageSize, offset)
          case _ => variantRepository.search("", pageSize, offset)
        }
        totalCount <- variantRepository.count(query.filter(_.nonEmpty))
      } yield {
        val totalPages = Math.max(1, (totalCount + pageSize - 1) / pageSize)
        Ok(views.html.curator.variants.listFragment(variants, query, page, totalPages, pageSize))
      }
    }

  def variantDetailPanel(id: Int): Action[AnyContent] =
    withPermission("variant.view").async { implicit request =>
      for {
        variantOpt <- variantRepository.findById(id)
        history <- auditService.getVariantHistory(id)
      } yield {
        variantOpt match {
          case Some(variant) =>
            Ok(views.html.curator.variants.detailPanel(variant, history))
          case None =>
            NotFound("Variant not found")
        }
      }
    }

  def createVariantForm: Action[AnyContent] =
    withPermission("variant.create").async { implicit request =>
      Future.successful(Ok(views.html.curator.variants.createForm(variantForm)))
    }

  def createVariant: Action[AnyContent] =
    withPermission("variant.create").async { implicit request =>
      variantForm.bindFromRequest().fold(
        formWithErrors => {
          Future.successful(BadRequest(views.html.curator.variants.createForm(formWithErrors)))
        },
        data => {
          val variant = Variant(
            variantId = None,
            genbankContigId = data.genbankContigId,
            position = data.position,
            referenceAllele = data.referenceAllele,
            alternateAllele = data.alternateAllele,
            variantType = data.variantType,
            rsId = data.rsId,
            commonName = data.commonName
          )

          for {
            newId <- variantRepository.createVariant(variant)
            createdVariant = variant.copy(variantId = Some(newId))
            _ <- auditService.logVariantCreate(request.user.id.get, createdVariant, Some("Created via curator interface"))
          } yield {
            Redirect(routes.CuratorController.listVariants(None, 1, 20))
              .flashing("success" -> s"Variant created successfully")
          }
        }
      )
    }

  def editVariantForm(id: Int): Action[AnyContent] =
    withPermission("variant.update").async { implicit request =>
      variantRepository.findById(id).map {
        case Some(variant) =>
          val formData = VariantFormData(
            genbankContigId = variant.genbankContigId,
            position = variant.position,
            referenceAllele = variant.referenceAllele,
            alternateAllele = variant.alternateAllele,
            variantType = variant.variantType,
            rsId = variant.rsId,
            commonName = variant.commonName
          )
          Ok(views.html.curator.variants.editForm(id, variantForm.fill(formData)))
        case None =>
          NotFound("Variant not found")
      }
    }

  def updateVariant(id: Int): Action[AnyContent] =
    withPermission("variant.update").async { implicit request =>
      variantRepository.findById(id).flatMap {
        case Some(oldVariant) =>
          variantForm.bindFromRequest().fold(
            formWithErrors => {
              Future.successful(BadRequest(views.html.curator.variants.editForm(id, formWithErrors)))
            },
            data => {
              val updatedVariant = oldVariant.copy(
                variantType = data.variantType,
                rsId = data.rsId,
                commonName = data.commonName
              )

              for {
                updated <- variantRepository.update(updatedVariant)
                _ <- if (updated) {
                  auditService.logVariantUpdate(request.user.id.get, oldVariant, updatedVariant, Some("Updated via curator interface"))
                } else {
                  Future.successful(())
                }
              } yield {
                if (updated) {
                  Redirect(routes.CuratorController.listVariants(None, 1, 20))
                    .flashing("success" -> "Variant updated successfully")
                } else {
                  BadRequest("Failed to update variant")
                }
              }
            }
          )
        case None =>
          Future.successful(NotFound("Variant not found"))
      }
    }

  def deleteVariant(id: Int): Action[AnyContent] =
    withPermission("variant.delete").async { implicit request =>
      variantRepository.findById(id).flatMap {
        case Some(variant) =>
          for {
            deleted <- variantRepository.delete(id)
            _ <- if (deleted) {
              auditService.logVariantDelete(request.user.id.get, variant, Some("Deleted via curator interface"))
            } else {
              Future.successful(())
            }
          } yield {
            if (deleted) {
              Ok("Deleted").withHeaders("HX-Trigger" -> "variantDeleted")
            } else {
              BadRequest("Failed to delete variant")
            }
          }
        case None =>
          Future.successful(NotFound("Variant not found"))
      }
    }

  // === Audit ===

  def auditHistory(entityType: String, entityId: Int): Action[AnyContent] =
    withPermission("audit.view").async { implicit request =>
      val historyFuture = entityType match {
        case "haplogroup" => auditService.getHaplogroupHistory(entityId)
        case "variant" => auditService.getVariantHistory(entityId)
        case _ => Future.successful(Seq.empty)
      }

      historyFuture.map { history =>
        Ok(views.html.curator.audit.historyPanel(entityType, entityId, history))
      }
    }
}
