package controllers

import actors.EnaStudyUpdateActor.UpdateSingleStudy
import models.forms.EnaAccessionSubmissionForm
import org.apache.pekko.actor.ActorRef
import play.api.i18n.I18nSupport
import play.api.mvc.*
import repositories.EnaStudyRepository

import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class EnaStudyController @Inject()(
                                    val controllerComponents: ControllerComponents,
                                    enaStudyRepository: EnaStudyRepository,
                                    @Named("ena-study-update-actor") enaUpdateActor: ActorRef,
                                    implicit val webJarsUtil: org.webjars.play.WebJarsUtil
                                  )(implicit ec: ExecutionContext)
  extends BaseController
    with I18nSupport {

  def showAccessionSubmissionForm(): Action[AnyContent] = Action { implicit request =>
    Ok(views.html.ena.submitAccession(EnaAccessionSubmissionForm.form))
  }

  def submitAccession(): Action[AnyContent] = Action.async { implicit request =>
    EnaAccessionSubmissionForm.form.bindFromRequest().fold(
      formWithErrors =>
        Future.successful(BadRequest(views.html.ena.submitAccession(formWithErrors))),
      submission => {
        val accession = submission.accession.trim
        enaStudyRepository.findByAccession(accession).flatMap {
          case Some(_) =>
            Future.successful(
              Redirect(routes.EnaStudyController.showAccessionSubmissionForm())
                .flashing("error" -> s"Study with accession $accession already exists")
            )
          case None =>
            enaUpdateActor ! UpdateSingleStudy(accession)
            Future.successful(
              Redirect(routes.EnaStudyController.showAccessionSubmissionForm())
                .flashing("success" -> s"Request to fetch ENA study with accession $accession has been queued")
            )
        }
      }
    )
  }
}