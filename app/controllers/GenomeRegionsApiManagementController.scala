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
 * Private API controller for managing genome regions, cytobands, and STR markers.
 * Secured with X-API-Key authentication.
 *
 * API changes are logged as "system" user in the audit log.
 */
@Singleton
class GenomeRegionsApiManagementController @Inject()(
  val controllerComponents: ControllerComponents,
  secureApi: ApiSecurityAction,
  managementService: GenomeRegionsManagementService
)(implicit ec: ExecutionContext) extends BaseController {

  private val logger = Logger(this.getClass)

  // ============================================================================
  // GenomeRegion Endpoints
  // ============================================================================

  def listRegions(build: Option[String], page: Int = 1, pageSize: Int = 25): Action[AnyContent] = secureApi.async { _ =>
    managementService.listRegions(build, page, pageSize).map { response =>
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

  // ============================================================================
  // Cytoband Endpoints
  // ============================================================================

  def listCytobands(build: Option[String], page: Int = 1, pageSize: Int = 25): Action[AnyContent] = secureApi.async { _ =>
    managementService.listCytobands(build, page, pageSize).map { response =>
      Ok(Json.toJson(response))
    }
  }

  def getCytoband(id: Int): Action[AnyContent] = secureApi.async { _ =>
    managementService.getCytoband(id).map {
      case Some(cytoband) => Ok(Json.toJson(cytoband))
      case None => NotFound(Json.obj("error" -> "Cytoband not found"))
    }
  }

  def createCytoband(): Action[CreateCytobandRequest] =
    secureApi.jsonAction[CreateCytobandRequest].async { request =>
      logger.info(s"API: Creating cytoband")
      managementService.createCytoband(request.body, None).map {
        case Right(cytoband) => Created(Json.toJson(cytoband))
        case Left(error) => BadRequest(Json.obj("error" -> error))
      }
    }

  def updateCytoband(id: Int): Action[UpdateCytobandRequest] =
    secureApi.jsonAction[UpdateCytobandRequest].async { request =>
      logger.info(s"API: Updating cytoband $id")
      managementService.updateCytoband(id, request.body, None).map {
        case Right(cytoband) => Ok(Json.toJson(cytoband))
        case Left(error) => BadRequest(Json.obj("error" -> error))
      }
    }

  def deleteCytoband(id: Int): Action[AnyContent] = secureApi.async { _ =>
    logger.info(s"API: Deleting cytoband $id")
    managementService.deleteCytoband(id, None).map {
      case Right(_) => NoContent
      case Left(error) => BadRequest(Json.obj("error" -> error))
    }
  }

  def bulkCreateCytobands(): Action[BulkCreateCytobandsRequest] =
    secureApi.jsonAction[BulkCreateCytobandsRequest].async { request =>
      logger.info(s"API: Bulk creating ${request.body.cytobands.size} cytobands")
      managementService.bulkCreateCytobands(request.body, None).map { response =>
        Ok(Json.toJson(response))
      }
    }

  // ============================================================================
  // STR Marker Endpoints
  // ============================================================================

  def listStrMarkers(build: Option[String], page: Int = 1, pageSize: Int = 25): Action[AnyContent] = secureApi.async { _ =>
    managementService.listStrMarkers(build, page, pageSize).map { response =>
      Ok(Json.toJson(response))
    }
  }

  def getStrMarker(id: Int): Action[AnyContent] = secureApi.async { _ =>
    managementService.getStrMarker(id).map {
      case Some(marker) => Ok(Json.toJson(marker))
      case None => NotFound(Json.obj("error" -> "STR marker not found"))
    }
  }

  def createStrMarker(): Action[CreateStrMarkerRequest] =
    secureApi.jsonAction[CreateStrMarkerRequest].async { request =>
      logger.info(s"API: Creating STR marker")
      managementService.createStrMarker(request.body, None).map {
        case Right(marker) => Created(Json.toJson(marker))
        case Left(error) => BadRequest(Json.obj("error" -> error))
      }
    }

  def updateStrMarker(id: Int): Action[UpdateStrMarkerRequest] =
    secureApi.jsonAction[UpdateStrMarkerRequest].async { request =>
      logger.info(s"API: Updating STR marker $id")
      managementService.updateStrMarker(id, request.body, None).map {
        case Right(marker) => Ok(Json.toJson(marker))
        case Left(error) => BadRequest(Json.obj("error" -> error))
      }
    }

  def deleteStrMarker(id: Int): Action[AnyContent] = secureApi.async { _ =>
    logger.info(s"API: Deleting STR marker $id")
    managementService.deleteStrMarker(id, None).map {
      case Right(_) => NoContent
      case Left(error) => BadRequest(Json.obj("error" -> error))
    }
  }

  def bulkCreateStrMarkers(): Action[BulkCreateStrMarkersRequest] =
    secureApi.jsonAction[BulkCreateStrMarkersRequest].async { request =>
      logger.info(s"API: Bulk creating ${request.body.markers.size} STR markers")
      managementService.bulkCreateStrMarkers(request.body, None).map { response =>
        Ok(Json.toJson(response))
      }
    }
}
