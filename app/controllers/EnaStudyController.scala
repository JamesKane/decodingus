package controllers

import models.domain.publications.EnaStudy
import org.webjars.play.WebJarsUtil
import play.api.libs.json.*
import play.api.mvc.*
import services.EnaIntegrationService

import javax.inject.*
import scala.concurrent.ExecutionContext

@Singleton
class EnaStudyController @Inject()(
                                    cc: ControllerComponents,
                                    enaService: EnaIntegrationService
                                  )(implicit ec: ExecutionContext, webJarsUtil: WebJarsUtil)
  extends AbstractController(cc) {

  def index(): Action[AnyContent] = Action { implicit request =>
    Ok(views.html.enasearch())
  }

  def getStudyDetails(accession: String): Action[AnyContent] = Action.async {
    enaService.getEnaStudyDetails(accession).map {
      case Some(study) => Ok(Json.toJson(study))
      case None => NotFound(Json.obj("message" -> s"Study not found for accession: $accession"))
    }
  }
}