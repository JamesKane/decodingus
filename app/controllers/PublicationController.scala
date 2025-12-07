package controllers

import models.forms.PaperSubmissionForm
import org.apache.pekko.actor.ActorRef
import org.webjars.play.WebJarsUtil
import play.api.Logging
import play.api.i18n.I18nSupport
import play.api.libs.json.Json
import play.api.mvc.*
import services.PublicationService

import javax.inject.*
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
                                       @Named("genomic-study-update-actor") studyUpdateActor: ActorRef
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
   * If a search query is provided, filters publications by title, authors, or abstract.
   *
   * @param page     An optional parameter specifying the current page number for paginated results.
   *                 Defaults to the first page if not provided.
   * @param pageSize An optional parameter specifying the number of items per page for paginated results.
   *                 Defaults to 10 if not provided.
   * @param query    An optional search query to filter publications by title, authors, or abstract.
   * @return An asynchronous action that renders an HTML view containing the paginated publication details.
   */
  def getAllPublicationsWithDetailsHtml(page: Option[Int], pageSize: Option[Int], query: Option[String]): Action[AnyContent] = Action.async { implicit request =>
    val currentPage = page.getOrElse(1)
    val currentPageSize = pageSize.getOrElse(10)
    val searchQuery = query.filter(_.trim.nonEmpty)

    val resultFuture = searchQuery match {
      case Some(q) => publicationService.searchPublications(q.trim, currentPage, currentPageSize)
      case None => publicationService.getPaginatedPublicationsWithDetails(currentPage, currentPageSize)
    }

    resultFuture.map { paginatedResult =>
      Ok(views.html.publicationList(paginatedResult, searchQuery))
    }
  }

  def showSubmissionForm(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.publications.submitPaper(PaperSubmissionForm.form))
  }

  def submitPaper() = Action.async { implicit request =>
    import actors.GenomicStudyUpdateActor.{UpdateStudy, UpdateResult}
    import models.domain.publications.StudySource
    import org.apache.pekko.pattern.ask
    import org.apache.pekko.util.Timeout
    import scala.concurrent.duration._

    implicit val timeout: Timeout = Timeout(30.seconds)

    PaperSubmissionForm.form.bindFromRequest().fold(
      formWithErrors =>
        Future.successful(BadRequest(views.html.publications.submitPaper(formWithErrors))),
      submission => {
        for {
          publicationOpt <- publicationService.processPublication(submission.doi, submission.forceRefresh)
          result <- publicationOpt match {
            case Some(publication) if submission.enaAccession.exists(_.nonEmpty) =>
              (studyUpdateActor ? UpdateStudy(
                submission.enaAccession.get,
                StudySource.ENA,
                Some(publication.id.get)
              )).mapTo[UpdateResult]
            case _ => Future.successful(UpdateResult("", true, "No ENA accession provided"))
          }
        } yield {
          if (result.success) {
            Redirect(routes.PublicationController.showSubmissionForm())
              .flashing("success" -> "Publication and associated data have been processed")
          } else {
            Redirect(routes.PublicationController.showSubmissionForm())
              .flashing("error" -> s"Error processing study: ${result.message}")
          }
        }
      }
    )
  }

}