package controllers

import models.forms.EnaBiosamplesSubmissionForm
import play.api.i18n.I18nSupport
import play.api.mvc.*
import services.EnaIntegrationService

import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class EnaBiosamplesTestController @Inject()(
                                             val controllerComponents: ControllerComponents,
                                             enaService: EnaIntegrationService,
                                             implicit val webJarsUtil: org.webjars.play.WebJarsUtil
                                           )(implicit ec: ExecutionContext)
  extends BaseController
    with I18nSupport {

  def showTestForm(): Action[AnyContent] = Action { implicit request =>
    Ok(views.html.ena.testBiosamples(EnaBiosamplesSubmissionForm.form))
  }

  def testBiosamples(): Action[AnyContent] = Action.async { implicit request =>
    EnaBiosamplesSubmissionForm.form.bindFromRequest().fold(
      formWithErrors =>
        Future.successful(BadRequest(views.html.ena.testBiosamples(formWithErrors))),
      submission => {
        val accession = submission.studyAccession.trim
        enaService.getBiosamplesForStudy(accession).map { biosamples =>
          Ok(views.html.ena.biosamplesResult(accession, biosamples))
        }
      }
    )
  }
}