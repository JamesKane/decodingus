package api

import models.api.{BiosampleOperationResponse, ExternalBiosampleRequest}
import services.firehose.{FirehoseEvent, FirehoseResult}
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.play.*
import play.api.libs.json.JsValue

import java.util.UUID

object FirehoseEndpoints {

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
    processEvent
  )
}
