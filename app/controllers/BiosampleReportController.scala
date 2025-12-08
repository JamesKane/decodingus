package controllers

import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, AbstractController, MessagesControllerComponents}
import play.api.i18n._ // Add this import
import services.BiosampleReportService

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

/**
 * Controller responsible for handling operations related to biosample reports.
 *
 * @param cc      Controller components used to handle HTTP-related features.
 * @param service Service layer used to fetch biosample data.
 * @param ec      ExecutionContext for handling asynchronous operations.
 */
@Singleton
class BiosampleReportController @Inject()(cc: MessagesControllerComponents, service: BiosampleReportService)(implicit ec: ExecutionContext) extends AbstractController(cc) with I18nSupport { // Modified
  // override protected def controllerComponents: ControllerComponents = cc // Not needed when extending AbstractController directly

  /**
   * Generates an HTML view of the biosample report for a specific publication. 
   * Supports pagination to display data across multiple pages.
   *
   * @param publicationId The ID of the publication for which the biosample report is generated.
   * @param page          Optional page number indicating which page of the report to retrieve.
   * @return An asynchronous Action that renders the HTML view of the biosample report.
   */
  def getBiosampleReportHTML(publicationId: Int, page: Option[Int]): Action[AnyContent] = Action.async { implicit request =>
    val currentPage = page.getOrElse(1)
    val pageSize = request.queryString.get("pageSize").flatMap(_.headOption).flatMap(_.toIntOption).getOrElse(100)

    service.getPaginatedBiosampleData(publicationId, currentPage, pageSize).map { paginatedResult =>
      Ok(views.html.biosampleReport(paginatedResult, publicationId))
    }
  }

  /**
   * Retrieves biosample data for a specific publication and returns it in JSON format.
   *
   * @param publicationId The ID of the publication for which biosample data will be retrieved.
   * @return An asynchronous Action that returns the biosample data as a JSON response.
   */
  def getBiosampleReportJSON(publicationId: Int): Action[AnyContent] = Action.async { implicit request =>
    service.getBiosampleData(publicationId).map { biosamples =>
      val jsonResponse = Json.toJson(biosamples)
      Ok(jsonResponse).as(play.api.http.MimeTypes.JSON)
    }
  }
}