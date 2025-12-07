package api

import models.api.{BiosampleOperationResponse, ExternalBiosampleRequest}
import services.firehose.{FirehoseEvent, FirehoseResult}
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.play.*
import play.api.libs.json.JsValue

import java.util.UUID

object CitizenBiosampleEndpoints {

  private val createBiosample: PublicEndpoint[ExternalBiosampleRequest, String, BiosampleOperationResponse, Any] = {
    endpoint
      .post
      .in("api" / "external-biosamples")
      .in(jsonBody[ExternalBiosampleRequest])
      .out(jsonBody[BiosampleOperationResponse])
      .errorOut(stringBody)
      .description("Creates a new Citizen Biosample with associated metadata and publication links. (Deprecated: Use /api/firehose/event)")
      .summary("Create Citizen Biosample (Legacy)")
      .tag("Citizen Biosamples")
      .deprecated()
  }

  private val updateBiosample: PublicEndpoint[(String, ExternalBiosampleRequest), String, BiosampleOperationResponse, Any] = {
    endpoint
      .put
      .in("api" / "external-biosamples" / path[String]("atUri"))
      .in(jsonBody[ExternalBiosampleRequest])
      .out(jsonBody[BiosampleOperationResponse])
      .errorOut(stringBody)
      .description("Updates an existing Citizen Biosample using Optimistic Locking (via atCid). (Deprecated: Use /api/firehose/event)")
      .summary("Update Citizen Biosample (Legacy)")
      .tag("Citizen Biosamples")
      .deprecated()
  }

  private val deleteBiosample: PublicEndpoint[String, String, Unit, Any] = {
    endpoint
      .delete
      .in("api" / "external-biosamples" / path[String]("atUri"))
      .out(statusCode(sttp.model.StatusCode.NoContent))
      .errorOut(stringBody)
      .description("Soft deletes a Citizen Biosample. (Deprecated: Use /api/firehose/event)")
      .summary("Delete Citizen Biosample (Legacy)")
      .tag("Citizen Biosamples")
      .deprecated()
  }

  private val processEvent: PublicEndpoint[JsValue, String, JsValue, Any] = {
    endpoint
      .post
      .in("api" / "firehose" / "event")
      .in(jsonBody[JsValue])
      .out(jsonBody[JsValue])
      .errorOut(stringBody)
      .description("Process a generic Atmosphere Lexicon event (Create/Update/Delete for any record type).")
      .summary("Process Atmosphere Event")
      .tag("Atmosphere Firehose")
  }

  val all: List[PublicEndpoint[_, _, _, _]] = List(
    createBiosample,
    updateBiosample,
    deleteBiosample,
    processEvent
  )
}
