package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Singleton}
import models.api.genomics.{SequencingLabCreateRequest, SequencingLabUpdateRequest}
import models.domain.genomics.SequencingLab
import play.api.libs.json.Json
import play.api.mvc.{AbstractController, Action, AnyContent, ControllerComponents}
import repositories.SequencingLabRepository

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

/**
 * Private CRUD APIs for managing sequencing_lab entries.
 * All endpoints are protected by ApiSecurityAction and are not exposed via Swagger (not Tapir-based).
 */
@Singleton
class SequencingLabAdminController @Inject()(
  cc: ControllerComponents,
  secureApi: ApiSecurityAction,
  labs: SequencingLabRepository
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  // List all labs
  def list: Action[AnyContent] = secureApi.async { _ =>
    labs.list().map(ls => Ok(Json.toJson(ls)))
  }

  // Get one lab by id
  def get(id: Int): Action[AnyContent] = secureApi.async { _ =>
    labs.findById(id).map {
      case Some(l) => Ok(Json.toJson(l))
      case None    => NotFound(Json.obj("error" -> s"Sequencing lab $id not found"))
    }
  }

  // Create a lab
  def create: Action[SequencingLabCreateRequest] = secureApi.jsonAction[SequencingLabCreateRequest].async { req =>
    val body = req.body
    val lab = SequencingLab(
      id = None,
      name = body.name,
      isD2c = body.isD2c.getOrElse(false),
      websiteUrl = body.websiteUrl,
      descriptionMarkdown = body.descriptionMarkdown,
      createdAt = LocalDateTime.now(),
      updatedAt = None
    )
    labs.create(lab).map(created => Created(Json.toJson(created)))
      .recover { case e => BadRequest(Json.obj("error" -> e.getMessage)) }
  }

  // Update a lab (partial)
  def update(id: Int): Action[SequencingLabUpdateRequest] = secureApi.jsonAction[SequencingLabUpdateRequest].async { req =>
    val patch = req.body
    labs.findById(id).flatMap {
      case None => Future.successful(NotFound(Json.obj("error" -> s"Sequencing lab $id not found")))
      case Some(existing) =>
        val updated = existing.copy(
          name = patch.name.getOrElse(existing.name),
          isD2c = patch.isD2c.getOrElse(existing.isD2c),
          websiteUrl = patch.websiteUrl.orElse(existing.websiteUrl),
          descriptionMarkdown = patch.descriptionMarkdown.orElse(existing.descriptionMarkdown),
          updatedAt = Some(LocalDateTime.now())
        )
        labs.update(id, updated).map {
          case Some(u) => Ok(Json.toJson(u))
          case None    => InternalServerError(Json.obj("error" -> "Failed to update sequencing lab"))
        }
    }
  }

  // Delete a lab
  def delete(id: Int): Action[AnyContent] = secureApi.async { _ =>
    labs.delete(id).map {
      case true  => NoContent
      case false => NotFound(Json.obj("error" -> s"Sequencing lab $id not found"))
    }
  }
}
