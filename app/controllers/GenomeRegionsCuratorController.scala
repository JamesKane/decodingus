package controllers

import actions.{AuthenticatedAction, AuthenticatedRequest, PermissionAction}
import config.GenomicsConfig
import jakarta.inject.{Inject, Singleton}
import models.api.genomics.*
import models.domain.genomics.GenbankContig
import org.webjars.play.WebJarsUtil
import play.api.Logging
import play.api.data.Form
import play.api.data.Forms.*
import play.api.i18n.I18nSupport
import play.api.mvc.*
import repositories.GenbankContigRepository
import services.GenomeRegionsManagementService

import scala.concurrent.{ExecutionContext, Future}

// Form data classes
case class GenomeRegionFormData(
  genbankContigId: Int,
  regionType: String,
  name: Option[String],
  startPos: Long,
  endPos: Long,
  modifier: Option[BigDecimal]
)

case class CytobandFormData(
  genbankContigId: Int,
  name: String,
  startPos: Long,
  endPos: Long,
  stain: String
)

case class StrMarkerFormData(
  genbankContigId: Int,
  name: String,
  startPos: Long,
  endPos: Long,
  period: Int,
  verified: Boolean,
  note: Option[String]
)

/**
 * UI Controller for managing genome regions, cytobands, and STR markers.
 * Uses session-based authentication with permission checks.
 */
@Singleton
class GenomeRegionsCuratorController @Inject()(
  val controllerComponents: ControllerComponents,
  authenticatedAction: AuthenticatedAction,
  permissionAction: PermissionAction,
  managementService: GenomeRegionsManagementService,
  genbankContigRepository: GenbankContigRepository,
  genomicsConfig: GenomicsConfig
)(implicit ec: ExecutionContext, webJarsUtil: WebJarsUtil)
  extends BaseController with I18nSupport with Logging {

  // Permission-based action composition
  private def withPermission(permission: String) =
    authenticatedAction andThen permissionAction(permission)

  // Forms
  private val genomeRegionForm: Form[GenomeRegionFormData] = Form(
    mapping(
      "genbankContigId" -> number,
      "regionType" -> nonEmptyText(1, 30),
      "name" -> optional(text(maxLength = 50)),
      "startPos" -> longNumber(min = 0),
      "endPos" -> longNumber(min = 0),
      "modifier" -> optional(bigDecimal(3, 2))
    )(GenomeRegionFormData.apply)(g => Some((g.genbankContigId, g.regionType, g.name, g.startPos, g.endPos, g.modifier)))
  )

  private val cytobandForm: Form[CytobandFormData] = Form(
    mapping(
      "genbankContigId" -> number,
      "name" -> nonEmptyText(1, 20),
      "startPos" -> longNumber(min = 0),
      "endPos" -> longNumber(min = 0),
      "stain" -> nonEmptyText(1, 10)
    )(CytobandFormData.apply)(c => Some((c.genbankContigId, c.name, c.startPos, c.endPos, c.stain)))
  )

  private val strMarkerForm: Form[StrMarkerFormData] = Form(
    mapping(
      "genbankContigId" -> number,
      "name" -> nonEmptyText(1, 30),
      "startPos" -> longNumber(min = 0),
      "endPos" -> longNumber(min = 0),
      "period" -> number(min = 1),
      "verified" -> boolean,
      "note" -> optional(text)
    )(StrMarkerFormData.apply)(s => Some((s.genbankContigId, s.name, s.startPos, s.endPos, s.period, s.verified, s.note)))
  )

  // ============================================================================
  // GenomeRegion UI Endpoints
  // ============================================================================

  def listRegions(build: Option[String], page: Int = 1, pageSize: Int = 25): Action[AnyContent] =
    withPermission("genome_region.view").async { implicit request =>
      for {
        response <- managementService.listRegions(build, page, pageSize)
      } yield {
        val totalPages = Math.max(1, (response.total + pageSize - 1) / pageSize)
        Ok(views.html.curator.genomeregions.list(response.regions, build, page, totalPages, pageSize, response.total, genomicsConfig.supportedReferences))
      }
    }

  def regionsFragment(build: Option[String], page: Int = 1, pageSize: Int = 25): Action[AnyContent] =
    withPermission("genome_region.view").async { implicit request =>
      for {
        response <- managementService.listRegions(build, page, pageSize)
      } yield {
        val totalPages = Math.max(1, (response.total + pageSize - 1) / pageSize)
        Ok(views.html.curator.genomeregions.listFragment(response.regions, build, page, totalPages, pageSize, response.total))
      }
    }

  def regionDetailPanel(id: Int): Action[AnyContent] =
    withPermission("genome_region.view").async { implicit request =>
      managementService.getRegion(id).map {
        case Some(region) => Ok(views.html.curator.genomeregions.detailPanel(region))
        case None => NotFound("Region not found")
      }
    }

  def createRegionForm: Action[AnyContent] =
    withPermission("genome_region.create").async { implicit request =>
      getContigsForForm.map { contigs =>
        Ok(views.html.curator.genomeregions.createForm(genomeRegionForm, contigs, genomicsConfig.supportedReferences))
      }
    }

  def createRegion: Action[AnyContent] =
    withPermission("genome_region.create").async { implicit request =>
      genomeRegionForm.bindFromRequest().fold(
        formWithErrors => {
          getContigsForForm.map { contigs =>
            BadRequest(views.html.curator.genomeregions.createForm(formWithErrors, contigs, genomicsConfig.supportedReferences))
          }
        },
        formData => {
          val createRequest = CreateGenomeRegionRequest(
            genbankContigId = formData.genbankContigId,
            regionType = formData.regionType,
            name = formData.name,
            startPos = formData.startPos,
            endPos = formData.endPos,
            modifier = formData.modifier
          )
          managementService.createRegion(createRequest, request.user.id).map {
            case Right(_) =>
              Redirect(routes.GenomeRegionsCuratorController.listRegions(None, 1, 25))
                .flashing("success" -> "Genome region created successfully")
            case Left(error) =>
              getContigsForFormSync.map { contigs =>
                BadRequest(views.html.curator.genomeregions.createForm(
                  genomeRegionForm.fill(formData).withGlobalError(error),
                  contigs,
                  genomicsConfig.supportedReferences
                ))
              }.getOrElse(BadRequest(error))
          }
        }
      )
    }

  def editRegionForm(id: Int): Action[AnyContent] =
    withPermission("genome_region.update").async { implicit request =>
      for {
        regionOpt <- managementService.getRegion(id)
        contigs <- getContigsForForm
      } yield regionOpt match {
        case Some(region) =>
          val formData = GenomeRegionFormData(
            region.genbankContigId,
            region.regionType,
            region.name,
            region.startPos,
            region.endPos,
            region.modifier
          )
          Ok(views.html.curator.genomeregions.editForm(id, genomeRegionForm.fill(formData), contigs, genomicsConfig.supportedReferences))
        case None =>
          NotFound("Region not found")
      }
    }

  def updateRegion(id: Int): Action[AnyContent] =
    withPermission("genome_region.update").async { implicit request =>
      genomeRegionForm.bindFromRequest().fold(
        formWithErrors => {
          getContigsForForm.map { contigs =>
            BadRequest(views.html.curator.genomeregions.editForm(id, formWithErrors, contigs, genomicsConfig.supportedReferences))
          }
        },
        formData => {
          val updateRequest = UpdateGenomeRegionRequest(
            regionType = Some(formData.regionType),
            name = formData.name,
            startPos = Some(formData.startPos),
            endPos = Some(formData.endPos),
            modifier = formData.modifier
          )
          managementService.updateRegion(id, updateRequest, request.user.id).map {
            case Right(_) =>
              Redirect(routes.GenomeRegionsCuratorController.listRegions(None, 1, 25))
                .flashing("success" -> "Genome region updated successfully")
            case Left(error) =>
              getContigsForFormSync.map { contigs =>
                BadRequest(views.html.curator.genomeregions.editForm(
                  id,
                  genomeRegionForm.fill(formData).withGlobalError(error),
                  contigs,
                  genomicsConfig.supportedReferences
                ))
              }.getOrElse(BadRequest(error))
          }
        }
      )
    }

  def deleteRegion(id: Int): Action[AnyContent] =
    withPermission("genome_region.delete").async { implicit request =>
      managementService.deleteRegion(id, request.user.id).map {
        case Right(_) =>
          Ok("").withHeaders("HX-Trigger" -> "regionDeleted")
        case Left(error) =>
          BadRequest(error)
      }
    }

  // ============================================================================
  // Cytoband UI Endpoints
  // ============================================================================

  def listCytobands(build: Option[String], page: Int = 1, pageSize: Int = 25): Action[AnyContent] =
    withPermission("cytoband.view").async { implicit request =>
      for {
        response <- managementService.listCytobands(build, page, pageSize)
      } yield {
        val totalPages = Math.max(1, (response.total + pageSize - 1) / pageSize)
        Ok(views.html.curator.cytobands.list(response.cytobands, build, page, totalPages, pageSize, response.total, genomicsConfig.supportedReferences))
      }
    }

  def cytobandsFragment(build: Option[String], page: Int = 1, pageSize: Int = 25): Action[AnyContent] =
    withPermission("cytoband.view").async { implicit request =>
      for {
        response <- managementService.listCytobands(build, page, pageSize)
      } yield {
        val totalPages = Math.max(1, (response.total + pageSize - 1) / pageSize)
        Ok(views.html.curator.cytobands.listFragment(response.cytobands, build, page, totalPages, pageSize, response.total))
      }
    }

  def cytobandDetailPanel(id: Int): Action[AnyContent] =
    withPermission("cytoband.view").async { implicit request =>
      managementService.getCytoband(id).map {
        case Some(cytoband) => Ok(views.html.curator.cytobands.detailPanel(cytoband))
        case None => NotFound("Cytoband not found")
      }
    }

  def createCytobandForm: Action[AnyContent] =
    withPermission("cytoband.create").async { implicit request =>
      getContigsForForm.map { contigs =>
        Ok(views.html.curator.cytobands.createForm(cytobandForm, contigs, genomicsConfig.supportedReferences))
      }
    }

  def createCytoband: Action[AnyContent] =
    withPermission("cytoband.create").async { implicit request =>
      cytobandForm.bindFromRequest().fold(
        formWithErrors => {
          getContigsForForm.map { contigs =>
            BadRequest(views.html.curator.cytobands.createForm(formWithErrors, contigs, genomicsConfig.supportedReferences))
          }
        },
        formData => {
          val createRequest = CreateCytobandRequest(
            genbankContigId = formData.genbankContigId,
            name = formData.name,
            startPos = formData.startPos,
            endPos = formData.endPos,
            stain = formData.stain
          )
          managementService.createCytoband(createRequest, request.user.id).map {
            case Right(_) =>
              Redirect(routes.GenomeRegionsCuratorController.listCytobands(None, 1, 25))
                .flashing("success" -> "Cytoband created successfully")
            case Left(error) =>
              Redirect(routes.GenomeRegionsCuratorController.createCytobandForm)
                .flashing("error" -> error)
          }
        }
      )
    }

  def editCytobandForm(id: Int): Action[AnyContent] =
    withPermission("cytoband.update").async { implicit request =>
      for {
        cytobandOpt <- managementService.getCytoband(id)
        contigs <- getContigsForForm
      } yield cytobandOpt match {
        case Some(cytoband) =>
          val formData = CytobandFormData(
            cytoband.genbankContigId,
            cytoband.name,
            cytoband.startPos,
            cytoband.endPos,
            cytoband.stain
          )
          Ok(views.html.curator.cytobands.editForm(id, cytobandForm.fill(formData), contigs, genomicsConfig.supportedReferences))
        case None =>
          NotFound("Cytoband not found")
      }
    }

  def updateCytoband(id: Int): Action[AnyContent] =
    withPermission("cytoband.update").async { implicit request =>
      cytobandForm.bindFromRequest().fold(
        formWithErrors => {
          getContigsForForm.map { contigs =>
            BadRequest(views.html.curator.cytobands.editForm(id, formWithErrors, contigs, genomicsConfig.supportedReferences))
          }
        },
        formData => {
          val updateRequest = UpdateCytobandRequest(
            name = Some(formData.name),
            startPos = Some(formData.startPos),
            endPos = Some(formData.endPos),
            stain = Some(formData.stain)
          )
          managementService.updateCytoband(id, updateRequest, request.user.id).map {
            case Right(_) =>
              Redirect(routes.GenomeRegionsCuratorController.listCytobands(None, 1, 25))
                .flashing("success" -> "Cytoband updated successfully")
            case Left(error) =>
              Redirect(routes.GenomeRegionsCuratorController.editCytobandForm(id))
                .flashing("error" -> error)
          }
        }
      )
    }

  def deleteCytoband(id: Int): Action[AnyContent] =
    withPermission("cytoband.delete").async { implicit request =>
      managementService.deleteCytoband(id, request.user.id).map {
        case Right(_) =>
          Ok("").withHeaders("HX-Trigger" -> "cytobandDeleted")
        case Left(error) =>
          BadRequest(error)
      }
    }

  // ============================================================================
  // STR Marker UI Endpoints
  // ============================================================================

  def listStrMarkers(build: Option[String], page: Int = 1, pageSize: Int = 25): Action[AnyContent] =
    withPermission("str_marker.view").async { implicit request =>
      for {
        response <- managementService.listStrMarkers(build, page, pageSize)
      } yield {
        val totalPages = Math.max(1, (response.total + pageSize - 1) / pageSize)
        Ok(views.html.curator.strmarkers.list(response.markers, build, page, totalPages, pageSize, response.total, genomicsConfig.supportedReferences))
      }
    }

  def strMarkersFragment(build: Option[String], page: Int = 1, pageSize: Int = 25): Action[AnyContent] =
    withPermission("str_marker.view").async { implicit request =>
      for {
        response <- managementService.listStrMarkers(build, page, pageSize)
      } yield {
        val totalPages = Math.max(1, (response.total + pageSize - 1) / pageSize)
        Ok(views.html.curator.strmarkers.listFragment(response.markers, build, page, totalPages, pageSize, response.total))
      }
    }

  def strMarkerDetailPanel(id: Int): Action[AnyContent] =
    withPermission("str_marker.view").async { implicit request =>
      managementService.getStrMarker(id).map {
        case Some(marker) => Ok(views.html.curator.strmarkers.detailPanel(marker))
        case None => NotFound("STR marker not found")
      }
    }

  def createStrMarkerForm: Action[AnyContent] =
    withPermission("str_marker.create").async { implicit request =>
      getContigsForForm.map { contigs =>
        Ok(views.html.curator.strmarkers.createForm(strMarkerForm, contigs, genomicsConfig.supportedReferences))
      }
    }

  def createStrMarker: Action[AnyContent] =
    withPermission("str_marker.create").async { implicit request =>
      strMarkerForm.bindFromRequest().fold(
        formWithErrors => {
          getContigsForForm.map { contigs =>
            BadRequest(views.html.curator.strmarkers.createForm(formWithErrors, contigs, genomicsConfig.supportedReferences))
          }
        },
        formData => {
          val createRequest = CreateStrMarkerRequest(
            genbankContigId = formData.genbankContigId,
            name = formData.name,
            startPos = formData.startPos,
            endPos = formData.endPos,
            period = formData.period,
            verified = formData.verified,
            note = formData.note
          )
          managementService.createStrMarker(createRequest, request.user.id).map {
            case Right(_) =>
              Redirect(routes.GenomeRegionsCuratorController.listStrMarkers(None, 1, 25))
                .flashing("success" -> "STR marker created successfully")
            case Left(error) =>
              Redirect(routes.GenomeRegionsCuratorController.createStrMarkerForm)
                .flashing("error" -> error)
          }
        }
      )
    }

  def editStrMarkerForm(id: Int): Action[AnyContent] =
    withPermission("str_marker.update").async { implicit request =>
      for {
        markerOpt <- managementService.getStrMarker(id)
        contigs <- getContigsForForm
      } yield markerOpt match {
        case Some(marker) =>
          val formData = StrMarkerFormData(
            marker.genbankContigId,
            marker.name,
            marker.startPos,
            marker.endPos,
            marker.period,
            marker.verified,
            marker.note
          )
          Ok(views.html.curator.strmarkers.editForm(id, strMarkerForm.fill(formData), contigs, genomicsConfig.supportedReferences))
        case None =>
          NotFound("STR marker not found")
      }
    }

  def updateStrMarker(id: Int): Action[AnyContent] =
    withPermission("str_marker.update").async { implicit request =>
      strMarkerForm.bindFromRequest().fold(
        formWithErrors => {
          getContigsForForm.map { contigs =>
            BadRequest(views.html.curator.strmarkers.editForm(id, formWithErrors, contigs, genomicsConfig.supportedReferences))
          }
        },
        formData => {
          val updateRequest = UpdateStrMarkerRequest(
            name = Some(formData.name),
            startPos = Some(formData.startPos),
            endPos = Some(formData.endPos),
            period = Some(formData.period),
            verified = Some(formData.verified),
            note = formData.note
          )
          managementService.updateStrMarker(id, updateRequest, request.user.id).map {
            case Right(_) =>
              Redirect(routes.GenomeRegionsCuratorController.listStrMarkers(None, 1, 25))
                .flashing("success" -> "STR marker updated successfully")
            case Left(error) =>
              Redirect(routes.GenomeRegionsCuratorController.editStrMarkerForm(id))
                .flashing("error" -> error)
          }
        }
      )
    }

  def deleteStrMarker(id: Int): Action[AnyContent] =
    withPermission("str_marker.delete").async { implicit request =>
      managementService.deleteStrMarker(id, request.user.id).map {
        case Right(_) =>
          Ok("").withHeaders("HX-Trigger" -> "strMarkerDeleted")
        case Left(error) =>
          BadRequest(error)
      }
    }

  // ============================================================================
  // Helper Methods
  // ============================================================================

  private def getContigsForForm: Future[Seq[GenbankContig]] = {
    // Get all contigs - they're pre-filtered by reference genome in the repository
    genbankContigRepository.getAll.map { contigs =>
      contigs.filter(c => c.referenceGenome.exists(genomicsConfig.supportedReferences.contains))
    }
  }

  private def getContigsForFormSync: Option[Seq[GenbankContig]] = {
    // This is a fallback for sync error handling - not ideal but simple
    None
  }
}
