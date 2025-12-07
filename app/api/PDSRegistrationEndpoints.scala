package api

import models.PDSRegistration
import play.api.libs.json.{Format, Json}
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.play.*

// --- DTOs (Data Transfer Objects) ---
case class PdsRegistrationRequest(
                                   did: String,
                                   handle: String,
                                   pdsUrl: String,
                                   rToken: String
                                 )

object PdsRegistrationRequest {
  implicit val format: Format[PdsRegistrationRequest] = Json.format[PdsRegistrationRequest]
}

object PDSRegistrationEndpoints {

  val registerPdsEndpoint: PublicEndpoint[PdsRegistrationRequest, String, PDSRegistration, Any] =
    endpoint.post
      .in("api" / "registerPDS")
      .name("Register PDS")
      .description("Registers a new PDS (Personal Data Server) with the system.")
      .in(jsonBody[PdsRegistrationRequest])
      .out(jsonBody[PDSRegistration])
      .errorOut(stringBody)

  val all = List(registerPdsEndpoint)
}
