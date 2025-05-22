package api

import models.api.{BiosampleWithOrigin, PublicationWithEnaStudiesAndSampleCount}
import sttp.tapir.*
import sttp.tapir.json.play.*
import sttp.tapir.generic.auto.*
import play.api.libs.json.*

import java.time.LocalDate

object ReferenceEndpoints {
  given Schema[LocalDate] = Schema.string.map((str: String) =>
    try Some(LocalDate.parse(str))
    catch case _: Exception => None
  )(_.toString)

  val getReferenceDetailsEndpoint: PublicEndpoint[Unit, String, List[PublicationWithEnaStudiesAndSampleCount], Any] = {
    endpoint
      .get
      .in("v1" / "references" / "details")
      .out(jsonBody[List[PublicationWithEnaStudiesAndSampleCount]])
      .errorOut(stringBody)
      .description("Returns a list of reference details, including publication information, ENA studies, and sample counts.")
      .summary("Retrieve details for references")
      .tag("References")
  }

  val getReferenceBiosamplesEndpoint: PublicEndpoint[Int, String, List[BiosampleWithOrigin], Any] = {
    endpoint
      .get
      .in("v1" / "references" / "details" / path[Int]("publicationId") / "biosamples")
      .out(jsonBody[List[BiosampleWithOrigin]])
      .errorOut(stringBody)
      .description("Returns a list of biosamples associated with a specific publication.")
      .summary("Retrieve biosamples for a publication")
      .tag("References")
  }

  val all: List[PublicEndpoint[_, _, _, _]] = List(
    getReferenceDetailsEndpoint,
    getReferenceBiosamplesEndpoint
  )
}
