package services

import com.google.inject.Inject
import play.api.libs.json.{JsError, JsSuccess, Json}
import play.api.libs.ws.WSClient
import play.api.{Configuration, Logging}

import scala.concurrent.duration.* // Import for FiniteDuration

import scala.concurrent.{ExecutionContext, Future}

/**
 * Service to interact with the AT Protocol for PDS (Personal Data Server) operations.
 *
 * This client provides methods to resolve DIDs to PDS endpoints and verify repository commits.
 *
 * @param ws The `WSClient` used for making HTTP requests.
 * @param configuration Play configuration for settings like timeouts.
 * @param ec The execution context for asynchronous operations.
 */
class ATProtocolClient @Inject()(
  ws: WSClient,
  configuration: Configuration
)(implicit ec: ExecutionContext) extends Logging {

  private val timeout: FiniteDuration = configuration.getOptional[Int]("atproto.client.timeout").getOrElse(5000).millis

  /**
   * Resolves a DID to its associated PDS endpoint URL.
   * This typically involves querying a DID resolver or a well-known endpoint on the PDS itself.
   *
   * @param did The Decentralized Identifier (DID) to resolve.
   * @return A Future containing the PDS URL if resolved, otherwise None.
   */
  def resolveHandle(handle: String): Future[Option[String]] = {
    // This is a simplified resolution. In a real scenario, this would involve a DID resolver service.
    // For now, we assume the handle can directly be used to construct a potential PDS URL for verification.
    // Or, more accurately, the PDS_URL is provided by the client, and this step is more about DID Document verification.
    // Based on the mermaid diagram, R_Edge verifies identity via resolveHandle.
    // The ScalaApp receives DID, R_Token, PDS_URL. So, we verify the PDS_URL against the DID.
    Future.successful(None) // Placeholder for actual implementation
  }

  /**
   * Verifies a PDS and retrieves its latest commit information using the provided authentication token.
   *
   * @param pdsUrl The base URL of the PDS.
   * @param repoDid The DID of the repository on the PDS.
   * @param authToken The authentication token (JWT) for accessing the PDS.
   * @return A Future containing `Option[LatestCommitResponse]` if successful, otherwise None.
   */
  def getLatestCommit(pdsUrl: String, repoDid: String, authToken: String): Future[Option[LatestCommitResponse]] = {
    val url = s"$pdsUrl/xrpc/com.atproto.repo.getCommit" // ATProto spec uses getCommit for this info

    ws.url(url)
      .addQueryStringParameters("repo" -> repoDid)
      .withHttpHeaders("Authorization" -> s"Bearer $authToken")
      .withRequestTimeout(timeout)
      .get()
      .map { response =>
        if (response.status == 200) {
          Json.fromJson[LatestCommitResponse](response.json) match {
            case JsSuccess(value, _) => Some(value)
            case JsError(errors) =>
              logger.error(s"Failed to parse getLatestCommit response from $pdsUrl for $repoDid: $errors")
              None
          }
        } else {
          logger.warn(s"Failed to get latest commit from $pdsUrl for $repoDid. Status: ${response.status}, Body: ${response.body}")
          None
        }
      }
      .recover {
        case e: Exception =>
          logger.error(s"Error calling getLatestCommit on $pdsUrl for $repoDid: ${e.getMessage}", e)
          None
      }
  }
}

// Define case class for the expected response from com.atproto.repo.getCommit
// This is a simplified representation. The actual response might be more complex.
// Based on AT Protocol spec, getCommit returns 'cid', 'rev', 'seq' etc.
case class LatestCommitResponse(
  cid: String, // The CID of the latest commit
  rev: String, // The repository revision
  seq: Long    // The sequence number of the latest commit
)

object LatestCommitResponse {
  implicit val format: play.api.libs.json.Format[LatestCommitResponse] = Json.format[LatestCommitResponse]
}
