package controllers

import actions.{AuthenticatedAction, RoleAction}
import jakarta.inject.{Inject, Singleton}
import play.api.Logging
import play.api.i18n.{I18nSupport, MessagesApi}
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import repositories.PublicationCandidateRepository
import services.PublicationDiscoveryService
import org.webjars.play.WebJarsUtil

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

  private def CuratorAction = authenticatedAction andThen roleAction("Curator", "Admin")

  private val validStatuses = Set("pending", "accepted", "rejected", "deferred")

  def listCandidates(page: Int = 1, pageSize: Int = 20, status: String = "pending"): Action[AnyContent] = CuratorAction.async { implicit request =>
    val effectiveStatus = if (validStatuses.contains(status)) status else "pending"

    for {
      (candidates, total) <- publicationCandidateRepository.listByStatus(effectiveStatus, page, pageSize)
      statusCounts <- publicationCandidateRepository.countByStatus()
    } yield {
      Ok(views.html.publicationCandidates.list(candidates, page, pageSize, total.toInt, effectiveStatus, statusCounts))
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

  def defer(id: Int): Action[AnyContent] = CuratorAction.async { implicit request =>
    val reviewerId = request.user.id.get

    publicationDiscoveryService.deferCandidate(id, reviewerId).map { success =>
      if (success) Redirect(routes.PublicationCandidateController.listCandidates())
        .flashing("success" -> messagesApi.preferred(request)("publicationCandidates.deferSuccess"))
      else Redirect(routes.PublicationCandidateController.listCandidates())
        .flashing("error" -> messagesApi.preferred(request)("publicationCandidates.deferFailed"))
    }
  }

  def bulkAction(): Action[AnyContent] = CuratorAction.async { implicit request =>
    val reviewerId = request.user.id.get
    val formData = request.body.asFormUrlEncoded.getOrElse(Map.empty)
    val MaxBulkSize = 500
    val ids = formData.getOrElse("candidateIds", Seq.empty).flatMap(_.split(",")).flatMap(_.toIntOption).take(MaxBulkSize).toSeq
    val action = formData.get("bulkAction").flatMap(_.headOption).getOrElse("")
    val reason = formData.get("reason").flatMap(_.headOption)

    if (ids.isEmpty) {
      Future.successful(
        Redirect(routes.PublicationCandidateController.listCandidates())
          .flashing("error" -> messagesApi.preferred(request)("publicationCandidates.bulk.noSelection"))
      )
    } else {
      val resultFuture = action match {
        case "accept" =>
          publicationDiscoveryService.bulkAcceptCandidates(ids, reviewerId).map { results =>
            val accepted = results.count(_.isDefined)
            messagesApi.preferred(request)("publicationCandidates.bulk.acceptSuccess", accepted.toString)
          }
        case "reject" =>
          publicationDiscoveryService.bulkRejectCandidates(ids, reviewerId, reason).map { count =>
            messagesApi.preferred(request)("publicationCandidates.bulk.rejectSuccess", count.toString)
          }
        case "defer" =>
          publicationDiscoveryService.bulkDeferCandidates(ids, reviewerId).map { count =>
            messagesApi.preferred(request)("publicationCandidates.bulk.deferSuccess", count.toString)
          }
        case _ =>
          Future.successful(messagesApi.preferred(request)("publicationCandidates.bulk.unknownAction"))
      }

      resultFuture.map { message =>
        Redirect(routes.PublicationCandidateController.listCandidates())
          .flashing("success" -> message)
      }.recover {
        case e: Exception =>
          logger.error(s"Error in bulk action '$action': ${e.getMessage}", e)
          Redirect(routes.PublicationCandidateController.listCandidates())
            .flashing("error" -> messagesApi.preferred(request)("publicationCandidates.bulk.error", e.getMessage))
      }
    }
  }
}
