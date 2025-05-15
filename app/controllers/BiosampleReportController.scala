package controllers

import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import services.BiosampleReportService

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class BiosampleReportController @Inject()(cc: ControllerComponents, service: BiosampleReportService)(implicit ec: ExecutionContext) extends BaseController {
  override protected def controllerComponents: ControllerComponents = cc

  def getBiosampleReportHTML(publicationId: Int, page: Option[Int]): Action[AnyContent] = Action.async { implicit request =>
    val currentPage = page.getOrElse(1)
    val pageSize = request.queryString.get("pageSize").flatMap(_.headOption).flatMap(_.toIntOption).getOrElse(100)

    service.getPaginatedBiosampleData(publicationId, currentPage, pageSize).map { paginatedResult =>
      Ok(views.html.biosampleReport(paginatedResult, publicationId))
    }
  }

  def getBiosampleReportJSON(publicationId: Int): Action[AnyContent] = Action.async { implicit request =>
    service.getBiosampleData(publicationId).map { biosamples =>
      val jsonResponse = Json.toJson(biosamples)
      Ok(jsonResponse).as(play.api.http.MimeTypes.JSON)
    }
  }
}