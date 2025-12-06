package api

import models.api.{BiosampleOperationResponse, ExternalBiosampleRequest}
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.play.*
import java.util.UUID

object CitizenBiosampleEndpoints {

  private val createBiosample: PublicEndpoint[ExternalBiosampleRequest, String, BiosampleOperationResponse, Any] = {
    endpoint
      .post
      .in("api" / "external-biosamples")
      .in(jsonBody[ExternalBiosampleRequest])
      .out(jsonBody[BiosampleOperationResponse])
      .errorOut(stringBody)
      .description("Creates a new Citizen Biosample with associated metadata and publication links.")
      .summary("Create Citizen Biosample")
      .tag("Citizen Biosamples")
  }

  private val updateBiosample: PublicEndpoint[(UUID, ExternalBiosampleRequest), String, BiosampleOperationResponse, Any] = {
    endpoint
      .put
      .in("api" / "external-biosamples" / path[UUID]("sampleGuid"))
      .in(jsonBody[ExternalBiosampleRequest])
      .out(jsonBody[BiosampleOperationResponse])
      .errorOut(stringBody)
      .description("Updates an existing Citizen Biosample using Optimistic Locking (via atCid).")
      .summary("Update Citizen Biosample")
      .tag("Citizen Biosamples")
  }

  private val deleteBiosample: PublicEndpoint[UUID, String, Unit, Any] = {
    endpoint
      .delete
      .in("api" / "external-biosamples" / path[UUID]("sampleGuid"))
      .out(statusCode(sttp.model.StatusCode.NoContent))
      .errorOut(stringBody)
      .description("Soft deletes a Citizen Biosample.")
      .summary("Delete Citizen Biosample")
      .tag("Citizen Biosamples")
  }

  val all: List[PublicEndpoint[_, _, _, _]] = List(
    createBiosample,
    updateBiosample,
    deleteBiosample
  )
}
