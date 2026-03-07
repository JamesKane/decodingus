package api

import play.api.libs.json.JsValue
import sttp.tapir.*
import sttp.tapir.json.play.*

object IbdRelayEndpoints {

  private val createSession: PublicEndpoint[JsValue, String, JsValue, Any] = {
    endpoint
      .post
      .in("api" / "v1" / "ibd" / "relay" / "session")
      .in(jsonBody[JsValue])
      .out(jsonBody[JsValue])
      .errorOut(stringBody)
      .summary("Create a relay session for IBD data exchange")
      .description("Requires mutual consent. Returns sessionId for WebSocket connection.")
      .tag("IBD Relay")
  }

  private val getSessionStatus: PublicEndpoint[String, String, JsValue, Any] = {
    endpoint
      .get
      .in("api" / "v1" / "ibd" / "relay" / "session" / path[String]("sessionId"))
      .out(jsonBody[JsValue])
      .errorOut(stringBody)
      .summary("Check relay session status")
      .tag("IBD Relay")
  }

  val all: List[PublicEndpoint[_, _, _, _]] = List(
    createSession,
    getSessionStatus
  )
}
