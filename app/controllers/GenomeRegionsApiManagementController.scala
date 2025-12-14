package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Singleton}
import models.api.genomics.*
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import services.GenomeRegionsManagementService

import scala.concurrent.{ExecutionContext, Future}

/**
 * Private API controller for managing genome regions.
 * Secured with X-API-Key authentication.
 *
 * API changes are logged as "system" user in the audit log.
 */
@Singleton
class GenomeRegionsApiManagementController @Inject()(
  val controllerComponents: ControllerComponents,
  secureApi: ApiSecurityAction,
  managementService: GenomeRegionsManagementService,
  ingestionService: services.genomics.GenomeRegionIngestionService
)(implicit ec: ExecutionContext) extends BaseController {

  private val logger = Logger(this.getClass)

  // ============================================================================
  // GenomeRegion Endpoints
  // ============================================================================

  def bootstrap(): Action[AnyContent] = secureApi.async { _ =>
    logger.info("API: Bootstrapping genome regions from CHM13v2.0 sources")
    ingestionService.bootstrap().map { _ =>
      Ok(Json.obj("message" -> "Genome regions bootstrapping completed successfully"))
    }
  }

  def listRegions(regionType: Option[String], build: Option[String], page: Int = 1, pageSize: Int = 25): Action[AnyContent] = secureApi.async { _ =>
    managementService.listRegions(regionType, build, page, pageSize).map { response =>
      Ok(Json.toJson(response))
    }
  }

  def getRegion(id: Int): Action[AnyContent] = secureApi.async { _ =>
    managementService.getRegion(id).map {
      case Some(region) => Ok(Json.toJson(region))
      case None => NotFound(Json.obj("error" -> "Region not found"))
    }
  }

  def createRegion(): Action[CreateGenomeRegionRequest] =
    secureApi.jsonAction[CreateGenomeRegionRequest].async { request =>
      logger.info(s"API: Creating genome region")
      managementService.createRegion(request.body, None).map {
        case Right(region) => Created(Json.toJson(region))
        case Left(error) => BadRequest(Json.obj("error" -> error))
      }
    }

  def updateRegion(id: Int): Action[UpdateGenomeRegionRequest] =
    secureApi.jsonAction[UpdateGenomeRegionRequest].async { request =>
      logger.info(s"API: Updating genome region $id")
      managementService.updateRegion(id, request.body, None).map {
        case Right(region) => Ok(Json.toJson(region))
        case Left(error) => BadRequest(Json.obj("error" -> error))
      }
    }

  def deleteRegion(id: Int): Action[AnyContent] = secureApi.async { _ =>
    logger.info(s"API: Deleting genome region $id")
    managementService.deleteRegion(id, None).map {
      case Right(_) => NoContent
      case Left(error) => BadRequest(Json.obj("error" -> error))
    }
  }

  def bulkCreateRegions(): Action[BulkCreateGenomeRegionsRequest] =
    secureApi.jsonAction[BulkCreateGenomeRegionsRequest].async { request =>
      logger.info(s"API: Bulk creating ${request.body.regions.size} genome regions")
      managementService.bulkCreateRegions(request.body, None).map { response =>
        Ok(Json.toJson(response))
      }
    }
}