package api

import models.api.SampleWithStudies
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.play.*

object SampleEndpoints {
  private val getSamplesWithStudies: PublicEndpoint[Unit, String, List[SampleWithStudies], Any] = {
    endpoint
      .get
      .in("api" / "v1" / "biosample" / "studies")
      .out(jsonBody[List[SampleWithStudies]])
      .errorOut(stringBody)
      .description("Returns a list of samples with their associated studies and haplogroup assignments")
      .summary("Get samples with studies and haplogroup assignments")
      .tag("Biosamples")
  }

  val all: List[PublicEndpoint[_, _, _, _]] = List(
    getSamplesWithStudies
  )
}