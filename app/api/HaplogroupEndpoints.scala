package api

import models.api.*
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.play.*

import java.time.ZonedDateTime

object HaplogroupEndpoints {

  given Schema[ZonedDateTime] = Schema.string.map((str: String) =>
    try Some(ZonedDateTime.parse(str))
    catch case _: Exception => None
  )(_.toString)

  private val getYTreeEnpoint: PublicEndpoint[Option[String], String, List[SubcladeDTO], Any] = {
    endpoint
      .get
      .in(
        "api" / "v1" / "y-tree" /
          query[Option[String]]("rootHaplogroup") // <--- Changed from path to query
            .description("The name of the subclade to use as root")
      )
      .out(jsonBody[List[SubcladeDTO]])
      .errorOut(stringBody)
      .description("Returns a list of YDNA tree nodes, defining variants and date of last update.")
      .summary("Returns a list of YDNA tree nodes")
      .tag("Haplogroups")
  }

  private val getMTreeEnpoint: PublicEndpoint[Option[String], String, List[SubcladeDTO], Any] = {
    endpoint
      .get
      .in("api" / "v1" / "mt-tree" / query[Option[String]]("rootHaplogroup").description("The name of the subclade to use as root"))
      .out(jsonBody[List[SubcladeDTO]])
      .errorOut(stringBody)
      .description("Returns a list of mtDNA tree nodes, defining variants and date of last update.")
      .summary("Returns a list of mtDNA tree nodes")
      .tag("Haplogroups")
  }

  val all: List[PublicEndpoint[_, _, _, _]] = List(
    getMTreeEnpoint,
    getYTreeEnpoint
  )
}
