package api

import play.api.libs.json.JsValue
import sttp.tapir.*
import sttp.tapir.json.play.*

object MatchRequestEndpoints {

  private val createRequest: PublicEndpoint[JsValue, String, JsValue, Any] = {
    endpoint
      .post
      .in("api" / "v1" / "matches" / "request")
      .in(jsonBody[JsValue])
      .out(jsonBody[JsValue])
      .errorOut(stringBody)
      .summary("Create a match request")
      .tag("Match Requests")
  }

  private val getPendingRequests: PublicEndpoint[String, String, JsValue, Any] = {
    endpoint
      .get
      .in("api" / "v1" / "matches" / "requests" / "pending")
      .in(query[String]("sampleGuid").description("Sample GUID to check pending requests for"))
      .out(jsonBody[JsValue])
      .errorOut(stringBody)
      .summary("Get pending match requests for a sample")
      .tag("Match Requests")
  }

  private val getSentRequests: PublicEndpoint[Unit, String, JsValue, Any] = {
    endpoint
      .get
      .in("api" / "v1" / "matches" / "requests" / "sent")
      .out(jsonBody[JsValue])
      .errorOut(stringBody)
      .summary("Get sent match requests")
      .tag("Match Requests")
  }

  private val cancelRequest: PublicEndpoint[String, String, JsValue, Any] = {
    endpoint
      .post
      .in("api" / "v1" / "matches" / "requests" / path[String]("uri") / "cancel")
      .out(jsonBody[JsValue])
      .errorOut(stringBody)
      .summary("Cancel a match request")
      .tag("Match Requests")
  }

  private val submitConsent: PublicEndpoint[JsValue, String, JsValue, Any] = {
    endpoint
      .post
      .in("api" / "v1" / "matches" / "consent")
      .in(jsonBody[JsValue])
      .out(jsonBody[JsValue])
      .errorOut(stringBody)
      .summary("Submit match consent")
      .tag("Match Requests")
  }

  private val getConsentStatus: PublicEndpoint[String, String, JsValue, Any] = {
    endpoint
      .get
      .in("api" / "v1" / "matches" / "consent" / "status" / path[String]("requestUri"))
      .out(jsonBody[JsValue])
      .errorOut(stringBody)
      .summary("Get consent status for a match request")
      .tag("Match Requests")
  }

  val all: List[PublicEndpoint[_, _, _, _]] = List(
    createRequest,
    getPendingRequests,
    getSentRequests,
    cancelRequest,
    submitConsent,
    getConsentStatus
  )
}
