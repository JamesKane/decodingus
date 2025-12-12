package controllers

import jakarta.inject.{Inject, Singleton}
import models.api.genomics.SupportedBuildsResponse
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import services.GenomeRegionsService

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

/**
 * Public API controller for genome region data.
 * Provides reference genome structural annotations including centromeres,
 * telomeres, cytobands, and Y-chromosome specific regions.
 */
@Singleton
class GenomeRegionsApiController @Inject()(
  val controllerComponents: ControllerComponents,
  genomeRegionsService: GenomeRegionsService
)(implicit ec: ExecutionContext) extends BaseController {

  private val CacheMaxAge = 7.days.toSeconds

  /**
   * List supported genome builds.
   * GET /api/v1/genome-regions
   */
  def listBuilds(): Action[AnyContent] = Action {
    val response = SupportedBuildsResponse(
      supportedBuilds = genomeRegionsService.getSupportedBuilds,
      version = "2024.12.1" // Default version
    )
    Ok(Json.toJson(response))
  }

  /**
   * Get genome regions for a specific build.
   * GET /api/v1/genome-regions/:build
   *
   * Supports ETag-based caching for efficient client-side caching.
   */
  def getRegions(build: String): Action[AnyContent] = Action.async { request =>
    // First get the ETag for this build
    genomeRegionsService.getETag(build).flatMap { etagOpt =>
      val clientEtag = request.headers.get("If-None-Match")

      // Check if client already has current version
      (etagOpt, clientEtag) match {
        case (Some(serverEtag), Some(requestEtag)) if serverEtag == requestEtag =>
          // Client has current version - return 304 Not Modified
          scala.concurrent.Future.successful(
            NotModified.withHeaders(
              "ETag" -> serverEtag,
              "Cache-Control" -> s"public, max-age=$CacheMaxAge"
            )
          )

        case _ =>
          // Need to return full response
          genomeRegionsService.getRegions(build).map {
            case Right(response) =>
              val headers = Seq(
                "Cache-Control" -> s"public, max-age=$CacheMaxAge",
                "Vary" -> "Accept-Encoding"
              ) ++ etagOpt.map("ETag" -> _)

              Ok(Json.toJson(response)).withHeaders(headers*)

            case Left(error) =>
              NotFound(Json.toJson(error))
          }
      }
    }.recover {
      case e: Exception =>
        InternalServerError(Json.obj(
          "error" -> "Internal error",
          "message" -> "Failed to load region data"
        ))
    }
  }
}
