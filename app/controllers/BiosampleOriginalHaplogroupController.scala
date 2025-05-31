package controllers

import actions.SecureApiAction
import jakarta.inject.Inject
import models.api.{BiosampleOriginalHaplogroupUpdate, BiosampleOriginalHaplogroupView}
import models.domain.publications.BiosampleOriginalHaplogroup
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import repositories.{BiosampleOriginalHaplogroupRepository, BiosampleRepository}

import scala.concurrent.{ExecutionContext, Future}

class BiosampleOriginalHaplogroupController @Inject()(
                                                       cc: ControllerComponents,
                                                       secureApi: SecureApiAction,
                                                       haplogroupRepository: BiosampleOriginalHaplogroupRepository,
                                                       biosampleRepository: BiosampleRepository
                                                     )(implicit ec: ExecutionContext) extends AbstractController(cc) {

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
                existingHaplogroup <- haplogroupRepository.findByBiosampleAndPublication(
                  biosampleId,
                  publicationId
                )
                result <- existingHaplogroup match {
                  case Some(existing) =>
                    val updated = existing.copy(
                      originalYHaplogroup = update.originalYHaplogroup.orElse(existing.originalYHaplogroup),
                      originalMtHaplogroup = update.originalMtHaplogroup.orElse(existing.originalMtHaplogroup),
                      notes = update.notes.orElse(existing.notes)
                    )
                    haplogroupRepository.update(updated).map(_ => updated)
                  case None =>
                    val newHaplogroup = BiosampleOriginalHaplogroup(
                      id = None,
                      biosampleId = biosampleId,
                      publicationId = publicationId,
                      originalYHaplogroup = update.originalYHaplogroup,
                      originalMtHaplogroup = update.originalMtHaplogroup,
                      notes = update.notes
                    )
                    haplogroupRepository.create(newHaplogroup)
                }
              } yield Ok(Json.toJson(BiosampleOriginalHaplogroupView.fromDomain(result)))).recover {
                case _: NoSuchElementException =>
                  NotFound(Json.obj("error" -> "Biosample not found"))
                case e: Exception =>
                  InternalServerError(Json.obj("error" -> e.getMessage))
              }
            }
          }
        )
      })
    }

  def getHaplogroup(biosampleId: Int, publicationId: Int): Action[AnyContent] =
    secureApi.async { request =>
      haplogroupRepository.findByBiosampleAndPublication(biosampleId, publicationId).map {
        case Some(haplogroup) => Ok(Json.toJson(BiosampleOriginalHaplogroupView.fromDomain(haplogroup)))
        case None => NotFound(Json.obj("error" -> "Haplogroup assignment not found"))
      }
    }

  def deleteHaplogroup(biosampleId: Int, publicationId: Int): Action[AnyContent] =
    secureApi.async { request =>
      haplogroupRepository.findByBiosampleAndPublication(biosampleId, publicationId).flatMap {
        case Some(haplogroup) =>
          haplogroupRepository.delete(haplogroup.id.get).map {
            case true => NoContent
            case false => InternalServerError(Json.obj("error" -> "Failed to delete haplogroup assignment"))
          }
        case None =>
          Future.successful(NotFound(Json.obj("error" -> "Haplogroup assignment not found")))
      }
    }


}