package controllers

import actions.{AuthenticatedAction, AuthenticatedRequest, PermissionAction}
import jakarta.inject.{Inject, Singleton}
import models.HaplogroupType
import models.dal.domain.genomics.Variant
import models.domain.genomics.{VariantGroup, VariantWithContig}
import models.domain.haplogroups.Haplogroup
import org.webjars.play.WebJarsUtil
import play.api.Logging
import play.api.data.Form
import play.api.data.Forms.*
import play.api.i18n.I18nSupport
import play.api.mvc.*
import repositories.{GenbankContigRepository, HaplogroupCoreRepository, HaplogroupVariantRepository, VariantAliasRepository, VariantRepository}
import services.{CuratorAuditService, TreeRestructuringService}
import services.genomics.YBrowseVariantIngestionService

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

case class HaplogroupFormData(
    name: String,
    lineage: Option[String],
    description: Option[String],
    haplogroupType: String,
    source: String,
    confidenceLevel: String,
    formedYbp: Option[Int],
    formedYbpLower: Option[Int],
    formedYbpUpper: Option[Int],
    tmrcaYbp: Option[Int],
    tmrcaYbpLower: Option[Int],
    tmrcaYbpUpper: Option[Int],
    ageEstimateSource: Option[String]
)

case class CreateHaplogroupFormData(
    name: String,
    lineage: Option[String],
    description: Option[String],
    haplogroupType: String,
    source: String,
    confidenceLevel: String,
    parentId: Option[Int],
    createAboveRoot: Boolean
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

case class SplitBranchFormData(
    name: String,
    lineage: Option[String],
    description: Option[String],
    source: String,
    confidenceLevel: String,
    variantGroupKeys: Seq[String],
    childIds: Seq[Int]
)

@Singleton
class CuratorController @Inject()(
    val controllerComponents: ControllerComponents,
    authenticatedAction: AuthenticatedAction,
    permissionAction: PermissionAction,
    haplogroupRepository: HaplogroupCoreRepository,
    variantRepository: VariantRepository,
    variantAliasRepository: VariantAliasRepository,
    haplogroupVariantRepository: HaplogroupVariantRepository,
    genbankContigRepository: GenbankContigRepository,
    auditService: CuratorAuditService,
    treeRestructuringService: TreeRestructuringService,
    variantIngestionService: YBrowseVariantIngestionService
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
      "confidenceLevel" -> nonEmptyText(1, 50),
      "formedYbp" -> optional(number),
      "formedYbpLower" -> optional(number),
      "formedYbpUpper" -> optional(number),
      "tmrcaYbp" -> optional(number),
      "tmrcaYbpLower" -> optional(number),
      "tmrcaYbpUpper" -> optional(number),
      "ageEstimateSource" -> optional(text(maxLength = 100))
    )(HaplogroupFormData.apply)(h => Some((h.name, h.lineage, h.description, h.haplogroupType, h.source, h.confidenceLevel, h.formedYbp, h.formedYbpLower, h.formedYbpUpper, h.tmrcaYbp, h.tmrcaYbpLower, h.tmrcaYbpUpper, h.ageEstimateSource)))
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

  private val splitBranchForm: Form[SplitBranchFormData] = Form(
    mapping(
      "name" -> nonEmptyText(1, 100),
      "lineage" -> optional(text(maxLength = 500)),
      "description" -> optional(text(maxLength = 2000)),
      "source" -> nonEmptyText(1, 100),
      "confidenceLevel" -> nonEmptyText(1, 50),
      "variantGroupKeys" -> seq(text),
      "childIds" -> seq(number)
    )(SplitBranchFormData.apply)(s => Some((s.name, s.lineage, s.description, s.source, s.confidenceLevel, s.variantGroupKeys, s.childIds)))
  )

  private val createHaplogroupFormMapping: Form[CreateHaplogroupFormData] = Form(
    mapping(
      "name" -> nonEmptyText(1, 100),
      "lineage" -> optional(text(maxLength = 500)),
      "description" -> optional(text(maxLength = 2000)),
      "haplogroupType" -> nonEmptyText.verifying("Invalid type", t => HaplogroupType.fromString(t).isDefined),
      "source" -> nonEmptyText(1, 100),
      "confidenceLevel" -> nonEmptyText(1, 50),
      "parentId" -> optional(number),
      "createAboveRoot" -> boolean
    )(CreateHaplogroupFormData.apply)(c => Some((c.name, c.lineage, c.description, c.haplogroupType, c.source, c.confidenceLevel, c.parentId, c.createAboveRoot)))
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
        variants <- haplogroupVariantRepository.getHaplogroupVariants(id)
        history <- auditService.getHaplogroupHistory(id)
      } yield {
        val variantsWithContig = variants.map { case (v, c) => VariantWithContig(v, c) }
        val variantGroups = variantRepository.groupVariants(variantsWithContig)
        haplogroupOpt match {
          case Some(haplogroup) =>
            Ok(views.html.curator.haplogroups.detailPanel(haplogroup, parentOpt, children, variantGroups, history))
          case None =>
            NotFound("Haplogroup not found")
        }
      }
    }

  def searchHaplogroupsJson(query: Option[String], hgType: Option[String]): Action[AnyContent] =
    withPermission("haplogroup.view").async { implicit request =>
      import play.api.libs.json.*
      val haplogroupType = hgType.flatMap(HaplogroupType.fromString)
      for {
        haplogroups <- haplogroupRepository.search(query.getOrElse(""), haplogroupType, 100, 0)
      } yield {
        val json = haplogroups.map { h =>
          Json.obj(
            "id" -> h.id,
            "name" -> h.name,
            "type" -> h.haplogroupType.toString
          )
        }
        Ok(Json.toJson(json))
      }
    }

  def createHaplogroupForm: Action[AnyContent] =
    withPermission("haplogroup.create").async { implicit request =>
      for {
        yRoots <- haplogroupRepository.findRoots(HaplogroupType.Y)
        mtRoots <- haplogroupRepository.findRoots(HaplogroupType.MT)
      } yield {
        Ok(views.html.curator.haplogroups.createForm(createHaplogroupFormMapping, yRoots, mtRoots))
      }
    }

  def createHaplogroup: Action[AnyContent] =
    withPermission("haplogroup.create").async { implicit request =>
      createHaplogroupFormMapping.bindFromRequest().fold(
        formWithErrors => {
          for {
            yRoots <- haplogroupRepository.findRoots(HaplogroupType.Y)
            mtRoots <- haplogroupRepository.findRoots(HaplogroupType.MT)
          } yield BadRequest(views.html.curator.haplogroups.createForm(formWithErrors, yRoots, mtRoots))
        },
        data => {
          val haplogroupType = HaplogroupType.fromString(data.haplogroupType).get
          val haplogroup = Haplogroup(
            id = None,
            name = data.name,
            lineage = data.lineage,
            description = data.description,
            haplogroupType = haplogroupType,
            revisionId = 1,
            source = data.source,
            confidenceLevel = data.confidenceLevel,
            validFrom = LocalDateTime.now(),
            validUntil = None
          )

          for {
            // Validate parent selection
            yRoots <- haplogroupRepository.findRoots(HaplogroupType.Y)
            mtRoots <- haplogroupRepository.findRoots(HaplogroupType.MT)
            existingRoots = if (haplogroupType == HaplogroupType.Y) yRoots else mtRoots

            result <- (data.parentId, data.createAboveRoot, existingRoots.nonEmpty) match {
              case (None, true, true) =>
                // Create as NEW root above existing roots
                for {
                  newId <- haplogroupRepository.createWithParent(haplogroup, None, "curator-create-above-root")
                  createdHaplogroup = haplogroup.copy(id = Some(newId))
                  // Re-parent all existing roots to become children of the new root
                  _ <- Future.traverse(existingRoots.flatMap(_.id)) { oldRootId =>
                    haplogroupRepository.updateParent(oldRootId, newId, "curator-create-above-root")
                  }
                  _ <- auditService.logHaplogroupCreate(
                    request.user.id.get,
                    createdHaplogroup,
                    Some(s"Created as new root above existing root(s): ${existingRoots.map(_.name).mkString(", ")}")
                  )
                } yield {
                  Redirect(routes.CuratorController.listHaplogroups(None, None, 1, 20))
                    .flashing("success" -> s"Haplogroup '${data.name}' created as new root. Previous root(s) are now children.")
                }

              case (None, false, true) =>
                // Trying to create a new root when one already exists without the flag
                val errorForm = createHaplogroupFormMapping.fill(data).withGlobalError(
                  s"A root haplogroup already exists for ${haplogroupType}. Select a parent (leaf), use 'Create above existing root', or use Split to create a subclade."
                )
                Future.successful(BadRequest(views.html.curator.haplogroups.createForm(errorForm, yRoots, mtRoots)))

              case (Some(parentId), _, _) =>
                // Validate parent exists and is of the same type
                haplogroupRepository.findById(parentId).flatMap {
                  case Some(parent) if parent.haplogroupType != haplogroupType =>
                    val errorForm = createHaplogroupFormMapping.fill(data).withGlobalError(
                      s"Parent haplogroup type (${parent.haplogroupType}) must match the new haplogroup type (${haplogroupType})"
                    )
                    Future.successful(BadRequest(views.html.curator.haplogroups.createForm(errorForm, yRoots, mtRoots)))

                  case Some(_) =>
                    // Create with parent (leaf)
                    for {
                      newId <- haplogroupRepository.createWithParent(haplogroup, Some(parentId), "curator-create")
                      createdHaplogroup = haplogroup.copy(id = Some(newId))
                      _ <- auditService.logHaplogroupCreate(request.user.id.get, createdHaplogroup, Some("Created as leaf via curator interface"))
                    } yield {
                      Redirect(routes.CuratorController.listHaplogroups(None, None, 1, 20))
                        .flashing("success" -> s"Haplogroup '${data.name}' created successfully as child of parent")
                    }

                  case None =>
                    val errorForm = createHaplogroupFormMapping.fill(data).withGlobalError("Selected parent haplogroup not found")
                    Future.successful(BadRequest(views.html.curator.haplogroups.createForm(errorForm, yRoots, mtRoots)))
                }

              case (None, _, false) =>
                // Create as new root (no existing roots for this type)
                for {
                  newId <- haplogroupRepository.createWithParent(haplogroup, None, "curator-create")
                  createdHaplogroup = haplogroup.copy(id = Some(newId))
                  _ <- auditService.logHaplogroupCreate(request.user.id.get, createdHaplogroup, Some("Created as root via curator interface"))
                } yield {
                  Redirect(routes.CuratorController.listHaplogroups(None, None, 1, 20))
                    .flashing("success" -> s"Haplogroup '${data.name}' created successfully as root")
                }
            }
          } yield result
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
            confidenceLevel = haplogroup.confidenceLevel,
            formedYbp = haplogroup.formedYbp,
            formedYbpLower = haplogroup.formedYbpLower,
            formedYbpUpper = haplogroup.formedYbpUpper,
            tmrcaYbp = haplogroup.tmrcaYbp,
            tmrcaYbpLower = haplogroup.tmrcaYbpLower,
            tmrcaYbpUpper = haplogroup.tmrcaYbpUpper,
            ageEstimateSource = haplogroup.ageEstimateSource
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
                confidenceLevel = data.confidenceLevel,
                formedYbp = data.formedYbp,
                formedYbpLower = data.formedYbpLower,
                formedYbpUpper = data.formedYbpUpper,
                tmrcaYbp = data.tmrcaYbp,
                tmrcaYbpLower = data.tmrcaYbpLower,
                tmrcaYbpUpper = data.tmrcaYbpUpper,
                ageEstimateSource = data.ageEstimateSource
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
      for {
        // Fetch grouped variants - note: pagination is approximate since we group after fetching
        variantGroups <- variantRepository.searchGrouped(query.getOrElse(""), pageSize * 3) // Fetch extra to ensure we have enough groups
      } yield {
        val pagedGroups = variantGroups.drop((page - 1) * pageSize).take(pageSize)
        val totalPages = Math.max(1, (variantGroups.size + pageSize - 1) / pageSize)
        Ok(views.html.curator.variants.list(pagedGroups, query, page, totalPages, pageSize))
      }
    }

  def variantsFragment(query: Option[String], page: Int, pageSize: Int): Action[AnyContent] =
    withPermission("variant.view").async { implicit request =>
      for {
        variantGroups <- variantRepository.searchGrouped(query.getOrElse(""), pageSize * 3)
      } yield {
        val pagedGroups = variantGroups.drop((page - 1) * pageSize).take(pageSize)
        val totalPages = Math.max(1, (variantGroups.size + pageSize - 1) / pageSize)
        Ok(views.html.curator.variants.listFragment(pagedGroups, query, page, totalPages, pageSize))
      }
    }

  def variantDetailPanel(id: Int): Action[AnyContent] =
    withPermission("variant.view").async { implicit request =>
      for {
        variantOpt <- variantRepository.findByIdWithContig(id)
        // Get all variants in the same group
        allVariantsInGroup <- variantOpt match {
          case Some(vwc) =>
            val groupKey = vwc.variant.commonName.orElse(vwc.variant.rsId).getOrElse(s"variant_${id}")
            variantRepository.getVariantsByGroupKey(groupKey)
          case None => Future.successful(Seq.empty)
        }
        // Fetch aliases for this variant
        aliases <- variantAliasRepository.findByVariantId(id)
        haplogroups <- haplogroupVariantRepository.getHaplogroupsByVariant(id)
        history <- auditService.getVariantHistory(id)
      } yield {
        variantOpt match {
          case Some(variantWithContig) =>
            val variantGroup = variantRepository.groupVariants(allVariantsInGroup).headOption
            Ok(views.html.curator.variants.detailPanel(variantWithContig, variantGroup, aliases, haplogroups, history))
          case None =>
            NotFound("Variant not found")
        }
      }
    }

  def createVariantForm: Action[AnyContent] =
    withPermission("variant.create").async { implicit request =>
      genbankContigRepository.getYAndMtContigs.map { contigs =>
        Ok(views.html.curator.variants.createForm(variantForm, contigs))
      }
    }

  def createVariant: Action[AnyContent] =
    withPermission("variant.create").async { implicit request =>
      variantForm.bindFromRequest().fold(
        formWithErrors => {
          genbankContigRepository.getYAndMtContigs.map { contigs =>
            BadRequest(views.html.curator.variants.createForm(formWithErrors, contigs))
          }
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
            // Create the source variant
            newId <- variantRepository.createVariant(variant)
            createdVariant = variant.copy(variantId = Some(newId))
            _ <- auditService.logVariantCreate(request.user.id.get, createdVariant, Some("Created via curator interface"))

            // Get the source contig for liftover
            sourceContigOpt <- genbankContigRepository.findById(data.genbankContigId)

            // Attempt liftover to other reference genomes
            liftedCount <- sourceContigOpt match {
              case Some(sourceContig) =>
                variantIngestionService.liftoverVariant(createdVariant, sourceContig).flatMap { liftedVariants =>
                  if (liftedVariants.nonEmpty) {
                    logger.info(s"Lifting variant ${data.commonName.getOrElse("unnamed")} to ${liftedVariants.size} other reference(s)")
                    // Create or find each lifted variant
                    variantRepository.findOrCreateVariantsBatch(liftedVariants).map(_.size)
                  } else {
                    Future.successful(0)
                  }
                }
              case None =>
                logger.warn(s"Source contig ${data.genbankContigId} not found for liftover")
                Future.successful(0)
            }
          } yield {
            val message = if (liftedCount > 0) {
              s"Variant created successfully. Also lifted to $liftedCount other reference genome(s)."
            } else {
              s"Variant created successfully. (Liftover to other references not available or failed)"
            }
            Redirect(routes.CuratorController.listVariants(None, 1, 20))
              .flashing("success" -> message)
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

  def editVariantGroupForm(groupKey: String): Action[AnyContent] =
    withPermission("variant.update").async { implicit request =>
      variantRepository.getVariantsByGroupKey(groupKey).map { variants =>
        if (variants.isEmpty) {
          NotFound("Variant group not found")
        } else {
          val variantGroup = variantRepository.groupVariants(variants).head
          // Use shared values from group for form
          val formData = VariantFormData(
            genbankContigId = variants.head.variant.genbankContigId,
            position = variants.head.variant.position,
            referenceAllele = variants.head.variant.referenceAllele,
            alternateAllele = variants.head.variant.alternateAllele,
            variantType = variants.head.variant.variantType,
            rsId = variantGroup.rsId,
            commonName = variantGroup.commonName
          )
          Ok(views.html.curator.variants.editGroupForm(groupKey, variantGroup, variantForm.fill(formData)))
        }
      }
    }

  def updateVariantGroup(groupKey: String): Action[AnyContent] =
    withPermission("variant.update").async { implicit request =>
      variantRepository.getVariantsByGroupKey(groupKey).flatMap { variants =>
        if (variants.isEmpty) {
          Future.successful(NotFound("Variant group not found"))
        } else {
          val variantGroup = variantRepository.groupVariants(variants).head
          variantForm.bindFromRequest().fold(
            formWithErrors => {
              Future.successful(BadRequest(views.html.curator.variants.editGroupForm(groupKey, variantGroup, formWithErrors)))
            },
            data => {
              // Update all variants in the group with the shared fields
              val updateFutures = variants.map { vwc =>
                val oldVariant = vwc.variant
                val updatedVariant = oldVariant.copy(
                  variantType = data.variantType,
                  rsId = data.rsId,
                  commonName = data.commonName
                )
                for {
                  updated <- variantRepository.update(updatedVariant)
                  _ <- if (updated) {
                    auditService.logVariantUpdate(request.user.id.get, oldVariant, updatedVariant, Some(s"Updated via group edit ($groupKey)"))
                  } else {
                    Future.successful(())
                  }
                } yield updated
              }

              Future.sequence(updateFutures).map { results =>
                if (results.forall(identity)) {
                  Redirect(routes.CuratorController.listVariants(None, 1, 20))
                    .flashing("success" -> s"Updated ${results.size} variants in group $groupKey")
                } else {
                  BadRequest(s"Failed to update some variants in group")
                }
              }
            }
          )
        }
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

  // === Haplogroup-Variant Associations ===

  def searchVariantsForHaplogroup(haplogroupId: Int, query: Option[String]): Action[AnyContent] =
    withPermission("haplogroup.view").async { implicit request =>
      for {
        haplogroupOpt <- haplogroupRepository.findById(haplogroupId)
        variantGroups <- query match {
          case Some(q) if q.nonEmpty => variantRepository.searchGrouped(q, 20)
          case _ => Future.successful(Seq.empty)
        }
        existingVariantIds <- haplogroupVariantRepository.getVariantsByHaplogroup(haplogroupId).map(_.flatMap(_.variantId).toSet)
      } yield {
        // Filter out groups where ALL variants are already associated
        val availableGroups = variantGroups.filterNot { group =>
          group.variantIds.forall(existingVariantIds.contains)
        }

        haplogroupOpt match {
          case Some(haplogroup) =>
            Ok(views.html.curator.haplogroups.variantSearchResults(haplogroupId, haplogroup.name, query, availableGroups))
          case None =>
            NotFound("Haplogroup not found")
        }
      }
    }

  def addVariantGroupToHaplogroup(haplogroupId: Int, groupKey: String): Action[AnyContent] =
    withPermission("haplogroup.update").async { implicit request =>
      for {
        // Get all variants in the group
        variantsInGroup <- variantRepository.getVariantsByGroupKey(groupKey)
        existingVariantIds <- haplogroupVariantRepository.getVariantsByHaplogroup(haplogroupId).map(_.flatMap(_.variantId).toSet)

        // Add each variant that isn't already associated
        addedIds <- Future.traverse(variantsInGroup.filterNot(v => existingVariantIds.contains(v.variant.variantId.getOrElse(-1)))) { vwc =>
          for {
            hvId <- haplogroupVariantRepository.addVariantToHaplogroup(haplogroupId, vwc.variant.variantId.get)
            _ <- auditService.logVariantAddedToHaplogroup(
              request.user.email.getOrElse(request.user.id.map(_.toString).getOrElse("unknown")),
              hvId,
              Some(s"Added variant ${vwc.variant.variantId.get} (${groupKey}) to haplogroup $haplogroupId")
            )
          } yield hvId
        }

        // Fetch updated variants for display
        variants <- haplogroupVariantRepository.getHaplogroupVariants(haplogroupId)
        variantsWithContig = variants.map { case (v, c) => VariantWithContig(v, c) }
        variantGroups = variantRepository.groupVariants(variantsWithContig)
      } yield {
        Ok(views.html.curator.haplogroups.variantsPanel(haplogroupId, variantGroups))
          .withHeaders("HX-Trigger" -> "variantAdded")
      }
    }

  def removeVariantGroupFromHaplogroup(haplogroupId: Int, groupKey: String): Action[AnyContent] =
    withPermission("haplogroup.update").async { implicit request =>
      for {
        // Get all variants in the group
        variantsInGroup <- variantRepository.getVariantsByGroupKey(groupKey)

        // Remove each variant
        removed <- Future.traverse(variantsInGroup.flatMap(_.variant.variantId)) { variantId =>
          haplogroupVariantRepository.removeVariantFromHaplogroup(haplogroupId, variantId)
        }

        // Fetch updated variants for display
        variants <- haplogroupVariantRepository.getHaplogroupVariants(haplogroupId)
        variantsWithContig = variants.map { case (v, c) => VariantWithContig(v, c) }
        variantGroups = variantRepository.groupVariants(variantsWithContig)
      } yield {
        if (removed.sum > 0) {
          Ok(views.html.curator.haplogroups.variantsPanel(haplogroupId, variantGroups))
            .withHeaders("HX-Trigger" -> "variantRemoved")
        } else {
          BadRequest("Failed to remove variant group")
        }
      }
    }

  def haplogroupVariantHistory(haplogroupVariantId: Int): Action[AnyContent] =
    withPermission("audit.view").async { implicit request =>
      auditService.getHaplogroupVariantHistory(haplogroupVariantId).map { history =>
        Ok(views.html.curator.haplogroups.variantHistoryPanel(haplogroupVariantId, history))
      }
    }

  // === Tree Restructuring ===

  def splitBranchForm(parentId: Int): Action[AnyContent] =
    withPermission("haplogroup.update").async { implicit request =>
      treeRestructuringService.getSplitPreview(parentId).map { preview =>
        Ok(views.html.curator.haplogroups.splitBranchForm(preview.parent, preview.variantGroups, preview.children, splitBranchForm))
      }.recover {
        case e: IllegalArgumentException =>
          NotFound(e.getMessage)
      }
    }

  def splitBranch(parentId: Int): Action[AnyContent] =
    withPermission("haplogroup.update").async { implicit request =>
      treeRestructuringService.getSplitPreview(parentId).flatMap { preview =>
        splitBranchForm.bindFromRequest().fold(
          formWithErrors => {
            Future.successful(BadRequest(views.html.curator.haplogroups.splitBranchForm(
              preview.parent, preview.variantGroups, preview.children, formWithErrors
            )))
          },
          data => {
            val newHaplogroup = Haplogroup(
              id = None,
              name = data.name,
              lineage = data.lineage,
              description = data.description,
              haplogroupType = preview.parent.haplogroupType,
              revisionId = 1,
              source = data.source,
              confidenceLevel = data.confidenceLevel,
              validFrom = LocalDateTime.now(),
              validUntil = None
            )

            treeRestructuringService.splitBranch(
              parentId,
              newHaplogroup,
              data.variantGroupKeys,
              data.childIds,
              request.user.id.get
            ).map { newId =>
              Redirect(routes.CuratorController.listHaplogroups(None, None, 1, 20))
                .flashing("success" -> s"Created subclade '${data.name}' under '${preview.parent.name}'")
            }.recover {
              case e: IllegalArgumentException =>
                BadRequest(views.html.curator.haplogroups.splitBranchForm(
                  preview.parent, preview.variantGroups, preview.children,
                  splitBranchForm.fill(data).withGlobalError(e.getMessage)
                ))
            }
          }
        )
      }
    }

  def mergeConfirmForm(childId: Int): Action[AnyContent] =
    withPermission("haplogroup.update").async { implicit request =>
      treeRestructuringService.getMergePreview(childId).map { preview =>
        Ok(views.html.curator.haplogroups.mergeConfirmForm(preview))
      }.recover {
        case e: IllegalArgumentException =>
          NotFound(e.getMessage)
      }
    }

  def mergeIntoParent(childId: Int): Action[AnyContent] =
    withPermission("haplogroup.update").async { implicit request =>
      treeRestructuringService.mergeIntoParent(childId, request.user.id.get).map { parentId =>
        Redirect(routes.CuratorController.haplogroupDetailPanel(parentId))
          .withHeaders("HX-Trigger" -> "haplogroupMerged")
      }.recover {
        case e: IllegalArgumentException =>
          BadRequest(e.getMessage)
      }
    }
}
