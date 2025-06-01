package controllers

import actions.SecureApiAction
import jakarta.inject.{Inject, Singleton}
import models.api.{PgpBiosampleRequest, PublicationInfo, SequenceDataInfo}
import play.api.libs.json.Json
import play.api.mvc.{Action, BaseController, ControllerComponents}
import services.PgpBiosampleService

import java.util.UUID
import scala.concurrent.ExecutionContext

@Singleton
class PgpBiosampleController @Inject()(
                                        val controllerComponents: ControllerComponents,
                                        secureApi: SecureApiAction,
                                        pgpBiosampleService: PgpBiosampleService
                                      )(implicit ec: ExecutionContext) extends BaseController {

  def create: Action[PgpBiosampleRequest] = secureApi.jsonAction[PgpBiosampleRequest].async { request =>
    pgpBiosampleService.createPgpBiosample(
      participantId = request.body.participantId,
      sampleDid = request.body.sampleDid,
      description = request.body.description,
      centerName = request.body.centerName,
      sex = request.body.sex
    ).map { guid =>
      Created(Json.toJson(guid))
    }
  }
}