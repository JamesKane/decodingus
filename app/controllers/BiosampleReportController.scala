package controllers

import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import services.BiosampleReportService

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class BiosampleReportController @Inject()(cc: ControllerComponents, service: BiosampleReportService)(implicit ec: ExecutionContext) extends BaseController {
  override protected def controllerComponents: ControllerComponents = cc

  def getBiosampleReportHTML(publicationId: Int): Action[AnyContent] = Action.async { implicit request =>
    service.getBiosampleData(publicationId).map { biosamples =>
      Ok(views.html.biosampleReport(biosamples))
    }
  }

  def getBiosampleReportJSON(publicationId: Int): Action[AnyContent] = Action.async { implicit request =>
    service.getBiosampleData(publicationId).map { biosamples =>
      val jsonResponse = Json.toJson(biosamples.map(bs => Json.obj("enaAccession" -> bs.enaAccession, "haplogroup" -> bs.yDnaHaplogroup)))
      Ok(jsonResponse).as(play.api.http.MimeTypes.JSON)
    }
  }
}