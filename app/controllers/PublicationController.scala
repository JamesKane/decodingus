package controllers

import actors.EnaStudyUpdateActor.{UpdateSingleStudy, UpdateResult}
import models.forms.PaperSubmissionForm
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.ask
import org.apache.pekko.util.Timeout
import org.webjars.play.WebJarsUtil
import play.api.Logging
import play.api.i18n.I18nSupport
import play.api.libs.json.Json
import play.api.mvc.*
import services.PublicationService

import javax.inject.*
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

/**
 * Controller for managing and serving publications.
 *
 * This controller handles actions related to displaying and retrieving publications.
 * It supports both JSON and HTML responses, allowing integration with front-end views
 * and API responses.
 *
 * @constructor Creates a new instance of `PublicationController`.
 * @param controllerComponents The controller components for managing HTTP actions.
 * @param publicationService   The service layer responsible for publication-related operations.
 * @param webJarsUtil          A utility for WebJars integration, provided implicitly.
 * @param ec                   An execution context for handling asynchronous operations, provided implicitly.
 */
@Singleton
class PublicationController @Inject()(
                                       val controllerComponents: ControllerComponents,
                                       publicationService: PublicationService,
                                       @Named("ena-study-update-actor") enaUpdateActor: ActorRef
                                     )
                                     (using webJarsUtil: WebJarsUtil, ec: ExecutionContext)
  extends BaseController with I18nSupport with Logging {
  /**
   * Renders the references page.
   *
   * This action handles GET requests to display the references page of the application.
   * It serves an HTML view with static content and links related to references.
   *
   * @return an action that renders the References view as an HTML response
   */
  def index(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.references())
  }


  /**
   * Returns all publications along with their associated details in JSON format.
   *
   * This method handles an asynchronous request to fetch all publications and their
   * detailed information, including associated studies and sample counts. The response
   * is serialized as JSON and returned with an HTTP OK status.
   *
   * @return an asynchronous action that produces an HTTP response containing JSON-encoded
   *         details of all publications.
   */
  def getAllPublicationsWithDetailsJson: Action[play.api.mvc.AnyContent] = Action.async {
    publicationService.getAllPublicationsWithDetails.map { publicationsWithDetails =>
      Ok(Json.toJson(publicationsWithDetails))
    }
  }

  /**
   * Fetches and displays a paginated list of publications along with their details in an HTML format.
   *
   * @param page     An optional parameter specifying the current page number for paginated results.
   *                 Defaults to the first page if not provided.
   * @param pageSize An optional parameter specifying the number of items per page for paginated results.
   *                 Defaults to 10 if not provided.
   * @return An asynchronous action that renders an HTML view containing the paginated publication details.
   */
  def getAllPublicationsWithDetailsHtml(page: Option[Int], pageSize: Option[Int]): Action[AnyContent] = Action.async { implicit request =>
    val currentPage = page.getOrElse(1)
    val currentPageSize = pageSize.getOrElse(10)
    publicationService.getPaginatedPublicationsWithDetails(currentPage, currentPageSize).map { paginatedResult =>
      Ok(views.html.publicationList(paginatedResult))
    }
  }

  def showSubmissionForm(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.publications.submitPaper(PaperSubmissionForm.form))
  }

  def submitPaper() = Action.async { implicit request =>
    PaperSubmissionForm.form.bindFromRequest().fold(
      formWithErrors =>
        Future.successful(BadRequest(views.html.publications.submitPaper(formWithErrors))),
      submission => {
        for {
          publicationOpt <- publicationService.processPublication(submission.doi, submission.forceRefresh)
          result <- publicationOpt match {
            case Some(publication) =>
              submission.enaAccession match {
                case Some(accession) if accession.nonEmpty =>
                  // Send message to actor and wait for response
                  implicit val timeout: Timeout = Timeout(30.seconds)
                  (enaUpdateActor ? UpdateSingleStudy(accession, Some(publication.id.get)))
                    .mapTo[UpdateResult]
                    .map {
                      case UpdateResult(_, true, msg) =>
                        logger.info(s"Successfully processed ENA study: $msg")
                        Right(())
                      case UpdateResult(_, false, msg) =>
                        logger.error(s"Failed to process ENA study: $msg")
                        Left(s"Failed to process ENA study: $msg")
                    }
                case _ => Future.successful(Right(()))
              }
            case None =>
              Future.successful(Left("Failed to process publication"))
          }
        } yield {
          result match {
            case Right(_) =>
              Redirect(routes.PublicationController.showSubmissionForm())
                .flashing("success" -> "Publication and associated data have been processed")
            case Left(error) =>
              Redirect(routes.PublicationController.showSubmissionForm())
                .flashing("error" -> error)
          }
        }
      }
    ).recover {
      case e: Exception =>
        logger.error("Error processing submission", e)
        Redirect(routes.PublicationController.showSubmissionForm())
          .flashing("error" -> s"Error processing submission: ${e.getMessage}")
    }
  }
}