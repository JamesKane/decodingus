package controllers

import jakarta.inject.{Inject, Singleton}
import models.dal.domain.genomics.Variant
import play.api.libs.json.{Json, OFormat}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import repositories.VariantRepository

import scala.concurrent.ExecutionContext

@Singleton
class VariantController @Inject()(
                                   val controllerComponents: ControllerComponents,
                                   variantRepository: VariantRepository
                                 )(implicit ec: ExecutionContext) extends BaseController {

  implicit val variantFormat: OFormat[Variant] = Json.format[Variant]

  /**
   * Searches for variants by name (rsId or commonName).
   *
   * @param name The name to search for (e.g., "rs123" or "M269").
   * @return A JSON array of matching variants.
   */
  def search(name: String): Action[AnyContent] = Action.async { implicit request =>
    variantRepository.searchByName(name).map { variants =>
      Ok(Json.toJson(variants))
    }
  }
}
