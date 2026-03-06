package controllers

import actions.ApiSecurityAction
import jakarta.inject.Inject
import models.api.{BiosampleOriginalHaplogroupUpdate, BiosampleOriginalHaplogroupView}
import models.domain.genomics.OriginalHaplogroupEntry
import play.api.Logging
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import repositories.{BiosampleOriginalHaplogroupRepository, BiosampleRepository}

import scala.concurrent.{ExecutionContext, Future}

class BiosampleOriginalHaplogroupController @Inject()(
                                                       cc: ControllerComponents,
                                                       secureApi: ApiSecurityAction,
                                                       haplogroupRepository: BiosampleOriginalHaplogroupRepository,
                                                       biosampleRepository: BiosampleRepository
                                                     )(implicit ec: ExecutionContext) extends AbstractController(cc) with Logging {

  def updateOrCreateHaplogroup(biosampleId: Int, publicationId: Int): Action[JsValue] =
    Action.async(parse.json) { request =>
      secureApi.invokeBlock(request, { secureRequest =>
        request.body.validate[BiosampleOriginalHaplogroupUpdate].fold(
          errors => Future.successful(BadRequest(Json.obj("error" -> "Invalid request format"))),
          update => {
            if (update.originalYHaplogroup.isEmpty &&
              update.originalMtHaplogroup.isEmpty &&
              update.notes.isEmpty) {
              Future.successful(BadRequest(Json.obj("error" -> "No valid fields to update")))
            } else {
              (for {
                biosampleExists <- biosampleRepository.findById(biosampleId)
                if biosampleExists.isDefined
                existing <- haplogroupRepository.findByBiosampleAndPublication(biosampleId, publicationId)
                entry = existing match {
                  case Some(e) =>
                    e.copy(
                      yHaplogroupResult = update.originalYHaplogroup.orElse(e.yHaplogroupResult),
                      mtHaplogroupResult = update.originalMtHaplogroup.orElse(e.mtHaplogroupResult),
                      notes = update.notes.orElse(e.notes)
                    )
                  case None =>
                    OriginalHaplogroupEntry(
                      publicationId = publicationId,
                      yHaplogroupResult = update.originalYHaplogroup,
                      mtHaplogroupResult = update.originalMtHaplogroup,
                      notes = update.notes
                    )
                }
                _ <- haplogroupRepository.upsert(biosampleId, entry)
              } yield Ok(Json.toJson(BiosampleOriginalHaplogroupView.fromEntry(biosampleId, entry)))).recover {
                case _: NoSuchElementException =>
                  NotFound(Json.obj("error" -> "Biosample not found"))
                case e: Exception =>
                  logger.error("Error updating haplogroup", e)
                  InternalServerError(Json.obj("error" -> "An internal error occurred."))
              }
            }
          }
        )
      })
    }

  def getHaplogroup(biosampleId: Int, publicationId: Int): Action[AnyContent] =
    secureApi.async { request =>
      haplogroupRepository.findByBiosampleAndPublication(biosampleId, publicationId).map {
        case Some(entry) => Ok(Json.toJson(BiosampleOriginalHaplogroupView.fromEntry(biosampleId, entry)))
        case None => NotFound(Json.obj("error" -> "Haplogroup assignment not found"))
      }
    }

  def deleteHaplogroup(biosampleId: Int, publicationId: Int): Action[AnyContent] =
    secureApi.async { request =>
      haplogroupRepository.findByBiosampleAndPublication(biosampleId, publicationId).flatMap {
        case Some(_) =>
          haplogroupRepository.delete(biosampleId, publicationId).map {
            case true => NoContent
            case false => InternalServerError(Json.obj("error" -> "Failed to delete haplogroup assignment"))
          }
        case None =>
          Future.successful(NotFound(Json.obj("error" -> "Haplogroup assignment not found")))
      }
    }
}
