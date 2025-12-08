package controllers

import actions.{AuthenticatedAction, RoleAction}
import jakarta.inject.{Inject, Singleton}
import play.api.Logging
import play.api.i18n.{I18nSupport, MessagesApi}
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import repositories.PublicationCandidateRepository
import services.PublicationDiscoveryService
import org.webjars.play.WebJarsUtil // Added import

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PublicationCandidateController @Inject()(
                                                val controllerComponents: ControllerComponents,
                                                publicationCandidateRepository: PublicationCandidateRepository,
                                                publicationDiscoveryService: PublicationDiscoveryService,
                                                override val messagesApi: MessagesApi,
                                                authenticatedAction: AuthenticatedAction,
                                                roleAction: RoleAction
                                              )(implicit ec: ExecutionContext, webJarsUtil: WebJarsUtil) extends BaseController with I18nSupport with Logging {

  // Combined action for authentication and RBAC
  // Allowing both 'Curator' and 'Admin' roles
  private def CuratorAction = authenticatedAction andThen roleAction("Curator", "Admin")

  // Renders the UI
  def listCandidates(page: Int = 1, pageSize: Int = 20): Action[AnyContent] = CuratorAction.async { implicit request =>
    // TODO: Fetch filters from request query string if needed
    publicationCandidateRepository.listPending(page, pageSize).map { case (candidates, total) =>
      Ok(views.html.publicationCandidates.list(candidates, page, pageSize, total.toInt))
    }
  }

  def accept(id: Int): Action[AnyContent] = CuratorAction.async { implicit request =>
    val reviewerId = request.user.id.get

    publicationDiscoveryService.acceptCandidate(id, reviewerId).map {
      case Some(pub) => Redirect(routes.PublicationCandidateController.listCandidates())
        .flashing("success" -> messagesApi.preferred(request)("publicationCandidates.acceptSuccess", pub.title))
      case None => Redirect(routes.PublicationCandidateController.listCandidates())
        .flashing("error" -> messagesApi.preferred(request)("publicationCandidates.acceptFailed"))
    }.recover {
      case e: Exception =>
        logger.error(s"Error accepting candidate $id: ${e.getMessage}", e)
        Redirect(routes.PublicationCandidateController.listCandidates())
          .flashing("error" -> messagesApi.preferred(request)("publicationCandidates.acceptError", e.getMessage))
    }
  }

  def reject(id: Int): Action[AnyContent] = CuratorAction.async { implicit request =>
    val reviewerId = request.user.id.get
    val reason = request.body.asFormUrlEncoded.flatMap(_.get("reason").flatMap(_.headOption))

    publicationDiscoveryService.rejectCandidate(id, reviewerId, reason).map { success =>
      if (success) Redirect(routes.PublicationCandidateController.listCandidates())
        .flashing("success" -> messagesApi.preferred(request)("publicationCandidates.rejectSuccess"))
      else Redirect(routes.PublicationCandidateController.listCandidates())
        .flashing("error" -> messagesApi.preferred(request)("publicationCandidates.rejectFailed"))
    }.recover {
      case e: Exception =>
        logger.error(s"Error rejecting candidate $id: ${e.getMessage}", e)
        Redirect(routes.PublicationCandidateController.listCandidates())
          .flashing("error" -> messagesApi.preferred(request)("publicationCandidates.rejectError", e.getMessage))
    }
  }
}
