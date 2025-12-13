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
import play.api.libs.json.Json
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

/**
 * UI Controller for managing genome regions.
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

  // ============================================================================
  // GenomeRegion UI Endpoints
  // ============================================================================

  def listRegions(regionType: Option[String], build: Option[String], page: Int = 1, pageSize: Int = 25): Action[AnyContent] =
    withPermission("genome_region.view").async { implicit request =>
      for {
        response <- managementService.listRegions(regionType, build, page, pageSize)
      } yield {
        val totalPages = Math.max(1, (response.total + pageSize - 1) / pageSize)
        Ok(views.html.curator.genomeregions.list(response.regions, build, page, totalPages, pageSize, response.total, genomicsConfig.supportedReferences))
      }
    }

  def regionsFragment(regionType: Option[String], build: Option[String], page: Int = 1, pageSize: Int = 25): Action[AnyContent] =
    withPermission("genome_region.view").async { implicit request =>
      for {
        response <- managementService.listRegions(regionType, build, page, pageSize)
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
          // Resolve contig to get build name and common name
          genbankContigRepository.getById(formData.genbankContigId).flatMap {
            case Some(contig) =>
              val build = contig.referenceGenome.getOrElse("unknown")
              val contigName = contig.commonName.getOrElse("unknown")
              
              val createRequest = CreateGenomeRegionRequest(
                regionType = formData.regionType,
                name = formData.name,
                coordinates = Map(build -> RegionCoordinateDto(contigName, formData.startPos, formData.endPos)),
                properties = formData.modifier.map(m => Json.obj("modifier" -> m))
              )
              
              managementService.createRegion(createRequest, request.user.id).map {
                case Right(_) =>
                  Redirect(routes.GenomeRegionsCuratorController.listRegions(None, None, 1, 25))
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
            case None =>
              Future.successful(BadRequest("Invalid contig ID"))
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
          // Try to map back to form data using the first coordinate found (limitation of this UI)
          val (build, coord) = region.coordinates.headOption.getOrElse("unknown" -> RegionCoordinateDto("", 0, 0))
          // We need a genbankContigId for the form dropdown. 
          // This is tricky without a reverse lookup or storing it.
          // For now, we might leave it 0 or try to find it in the list of contigs if possible.
          // Or just pick the first contig that matches name and build.
          val contigId = contigs.find(c => c.commonName.contains(coord.contig) && c.referenceGenome.contains(build))
            .flatMap(_.id).getOrElse(0)

          val modifier = (region.properties \ "modifier").asOpt[BigDecimal]

          val formData = GenomeRegionFormData(
            contigId,
            region.regionType,
            region.name,
            coord.start,
            coord.end,
            modifier
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
          genbankContigRepository.getById(formData.genbankContigId).flatMap {
            case Some(contig) =>
              val build = contig.referenceGenome.getOrElse("unknown")
              val contigName = contig.commonName.getOrElse("unknown")

              val updateRequest = UpdateGenomeRegionRequest(
                regionType = Some(formData.regionType),
                name = formData.name,
                // Merging coordinates is complex. This simplistic update might overwrite other builds' coordinates
                // if the service replaces the map. The Service logic currently REPLACES if provided.
                // To support multi-build editing, the UI needs to change.
                // For now, we assume single-build editing flow.
                coordinates = Some(Map(build -> RegionCoordinateDto(contigName, formData.startPos, formData.endPos))),
                properties = formData.modifier.map(m => Json.obj("modifier" -> m))
              )

              managementService.updateRegion(id, updateRequest, request.user.id).map {
                case Right(_) =>
                  Redirect(routes.GenomeRegionsCuratorController.listRegions(None, None, 1, 25))
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
             case None => Future.successful(BadRequest("Invalid Contig")) 
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