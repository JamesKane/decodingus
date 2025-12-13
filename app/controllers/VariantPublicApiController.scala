package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Named, Singleton}
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import services.{ExportResult, VariantExportService, VariantPublicApiService}

import java.nio.file.Files
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

/**
 * Public API controller for variant data.
 * Provides read-only access to variant information with forward-compatible response format.
 */
@Singleton
class VariantPublicApiController @Inject()(
                                            val controllerComponents: ControllerComponents,
                                            variantPublicApiService: VariantPublicApiService,
                                            variantExportService: VariantExportService,
                                            secureApi: ApiSecurityAction,
                                            @Named("variant-export-actor") variantExportActor: ActorRef
                                          )(implicit ec: ExecutionContext) extends BaseController {

  /**
   * Search variants with pagination.
   * GET /api/v1/variants?query=M269&page=1&pageSize=25
   */
  def searchVariants(query: Option[String], page: Int, pageSize: Int): Action[AnyContent] = Action.async {
    val validPage = Math.max(1, page)
    val validPageSize = Math.min(100, Math.max(1, pageSize))

    variantPublicApiService.searchVariants(query, validPage, validPageSize).map { response =>
      Ok(Json.toJson(response))
    }.recover {
      case e: Exception =>
        InternalServerError(Json.obj("error" -> e.getMessage))
    }
  }

  /**
   * Get a single variant by ID.
   * GET /api/v1/variants/:id
   */
  def getVariantById(variantId: Int): Action[AnyContent] = Action.async {
    variantPublicApiService.getVariantById(variantId).map {
      case Some(variant) => Ok(Json.toJson(variant))
      case None => NotFound(Json.obj("error" -> s"Variant not found: $variantId"))
    }.recover {
      case e: Exception =>
        InternalServerError(Json.obj("error" -> e.getMessage))
    }
  }

  /**
   * Get all variants defining a specific haplogroup.
   * GET /api/v1/haplogroups/:name/variants
   */
  def getVariantsByHaplogroup(haplogroupName: String): Action[AnyContent] = Action.async {
    variantPublicApiService.getVariantsByHaplogroup(haplogroupName).map { variants =>
      Ok(Json.toJson(variants))
    }.recover {
      case e: Exception =>
        InternalServerError(Json.obj("error" -> e.getMessage))
    }
  }

  /**
   * Download the full variant export file (gzipped JSONL).
   * GET /api/v1/variants/export
   *
   * Returns 404 if no export file exists yet.
   * Returns the pre-generated file with metadata headers.
   */
  def downloadExport(): Action[AnyContent] = Action { request =>
    val exportPath = variantExportService.getExportFilePath

    if (Files.exists(exportPath)) {
      val metadata = variantExportService.getExportMetadata

      Ok.sendFile(
        content = exportPath.toFile,
        fileName = _ => Some("variants-full.jsonl.gz")
      ).withHeaders(
        "Content-Type" -> "application/gzip",
        "X-Variant-Count" -> metadata.map(_.variantCount.toString).getOrElse("unknown"),
        "X-Generated-At" -> metadata.map(_.generatedAt.toString).getOrElse("unknown")
      )
    } else {
      NotFound(Json.obj(
        "error" -> "Export file not yet generated",
        "message" -> "The variant export is generated daily at 4 AM UTC. Please try again later or trigger a manual generation."
      ))
    }
  }

  /**
   * Get metadata about the current export file.
   * GET /api/v1/variants/export/metadata
   */
  def exportMetadata(): Action[AnyContent] = Action {
    variantExportService.getExportMetadata match {
      case Some(metadata) =>
        Ok(Json.toJson(metadata))
      case None =>
        NotFound(Json.obj("error" -> "No export metadata available"))
    }
  }

  /**
   * Trigger manual export generation (admin only, requires X-API-Key).
   * POST /api/private/variants/export/generate
   */
  def triggerExport(): Action[AnyContent] = secureApi.async { _ =>
    import actors.VariantExportActor.RunExport
    implicit val timeout: Timeout = Timeout(30.minutes)

    (variantExportActor ? RunExport).mapTo[ExportResult].map { result =>
      if (result.success) {
        Ok(Json.toJson(result))
      } else {
        InternalServerError(Json.toJson(result))
      }
    }.recover {
      case e: Exception =>
        InternalServerError(Json.obj("error" -> e.getMessage))
    }
  }
}
