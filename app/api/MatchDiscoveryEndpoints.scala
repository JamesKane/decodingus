package api

import play.api.libs.json.JsValue
import sttp.tapir.*
import sttp.tapir.json.play.*

object MatchDiscoveryEndpoints {

  private val getSuggestions: PublicEndpoint[(Option[String], Int, String), String, JsValue, Any] = {
    endpoint
      .get
      .in("api" / "v1" / "discovery" / "suggestions")
      .in(query[Option[String]]("type").description("Filter by suggestion type"))
      .in(query[Int]("limit").default(20).description("Max results"))
      .in(query[String]("sampleGuid").description("Target sample GUID"))
      .out(jsonBody[JsValue])
      .errorOut(stringBody)
      .summary("Get match suggestions for a sample")
      .tag("Match Discovery")
  }

  private val dismissSuggestion: PublicEndpoint[Long, String, JsValue, Any] = {
    endpoint
      .post
      .in("api" / "v1" / "discovery" / "suggestions" / path[Long]("id") / "dismiss")
      .out(jsonBody[JsValue])
      .errorOut(stringBody)
      .summary("Dismiss a match suggestion")
      .tag("Match Discovery")
  }

  private val getPopulationBreakdown: PublicEndpoint[String, String, JsValue, Any] = {
    endpoint
      .get
      .in("api" / "v1" / "discovery" / "population" / path[String]("sampleGuid"))
      .out(jsonBody[JsValue])
      .errorOut(stringBody)
      .summary("Get population breakdown for a sample")
      .tag("Match Discovery")
  }

  private val getPopulationOverlap: PublicEndpoint[(String, String), String, JsValue, Any] = {
    endpoint
      .get
      .in("api" / "v1" / "discovery" / "population" / "overlap" / path[String]("guid1") / path[String]("guid2"))
      .out(jsonBody[JsValue])
      .errorOut(stringBody)
      .summary("Get population overlap score between two samples")
      .tag("Match Discovery")
  }

  val all: List[PublicEndpoint[_, _, _, _]] = List(
    getSuggestions,
    dismissSuggestion,
    getPopulationBreakdown,
    getPopulationOverlap
  )
}
