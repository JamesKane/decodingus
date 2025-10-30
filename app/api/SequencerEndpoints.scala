package api

import models.api.SequencerLabInfo
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.play.*

object SequencerEndpoints {

  private val getLabByInstrumentId: PublicEndpoint[String, String, SequencerLabInfo, Any] = {
    endpoint
      .get
      .in("api" / "v1" / "sequencer" / "lab")
      .in(query[String]("instrument_id")
        .description("The unique instrument ID from BAM/CRAM read headers (e.g., 'A00123')")
        .example("A00123"))
      .out(jsonBody[SequencerLabInfo])
      .errorOut(stringBody)
      .description("Returns sequencing lab information for a given instrument ID extracted from BAM/CRAM headers")
      .summary("Get lab info by instrument ID")
      .tag("Sequencer")
  }

  val all: List[PublicEndpoint[_, _, _, _]] = List(
    getLabByInstrumentId
  )
}
