package services

import com.google.inject.Inject
import play.api.libs.json.{JsError, JsSuccess, Json, JsValue}
import play.api.libs.ws.WSClient
import play.api.{Configuration, Logging}
import play.api.libs.ws.JsonBodyWritables._

import javax.naming.directory.{InitialDirContext, Attributes}
import java.util.Hashtable
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Try, Success, Failure}

/**
 * Service to interact with the AT Protocol for PDS (Personal Data Server) operations.
 *
 * This client provides methods to resolve DIDs to PDS endpoints and verify repository commits.
 *
 * @param ws            The `WSClient` used for making HTTP requests.
 * @param configuration Play configuration for settings like timeouts.
 * @param ec            The execution context for asynchronous operations.
 */
class ATProtocolClient @Inject()(
                                  ws: WSClient,
                                  configuration: Configuration
                                )(implicit ec: ExecutionContext) extends Logging {

  private val timeout: FiniteDuration = configuration.getOptional[Int]("atproto.client.timeout").getOrElse(5000).millis

  // PLC Directory for did:plc resolution
  private val plcDirectoryUrl = configuration.getOptional[String]("atproto.plc.directory")
    .getOrElse("https://plc.directory")

  /**
   * Resolves a handle to its DID using the AT Protocol handle resolution mechanism.
   *
   * Resolution order (per AT Protocol spec):
   * 1. DNS TXT record at _atproto.{handle}
   * 2. HTTPS well-known at https://{handle}/.well-known/atproto-did
   *
   * @param handle The handle to resolve (e.g., "alice.bsky.social" or "alice.example.com")
   * @return A Future containing the DID if resolved, otherwise None.
   */
  def resolveHandle(handle: String): Future[Option[String]] = {
    // Normalize handle (remove @ prefix if present)
    val normalizedHandle = handle.stripPrefix("@").toLowerCase

    // Try DNS first, then fall back to well-known
    resolveHandleViaDns(normalizedHandle).flatMap {
      case Some(did) =>
        logger.debug(s"Resolved handle $normalizedHandle via DNS to $did")
        Future.successful(Some(did))
      case None =>
        resolveHandleViaWellKnown(normalizedHandle).map { didOpt =>
          didOpt.foreach(did => logger.debug(s"Resolved handle $normalizedHandle via well-known to $did"))
          didOpt
        }
    }
  }

  /**
   * Resolves a handle via DNS TXT record lookup.
   * Looks for TXT record at _atproto.{handle} containing "did=did:plc:xxx" or "did=did:web:xxx"
   */
  private def resolveHandleViaDns(handle: String): Future[Option[String]] = Future {
    Try {
      val env = new Hashtable[String, String]()
      env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory")

      val ctx = new InitialDirContext(env)
      try {
        val attrs: Attributes = ctx.getAttributes(s"_atproto.$handle", Array("TXT"))
        val txtRecord = attrs.get("TXT")

        if (txtRecord != null && txtRecord.size() > 0) {
          val value = txtRecord.get(0).toString.replaceAll("\"", "")
          // TXT record format: "did=did:plc:xxxx" or "did=did:web:xxxx"
          if (value.startsWith("did=")) {
            Some(value.substring(4))
          } else {
            None
          }
        } else {
          None
        }
      } finally {
        ctx.close()
      }
    } match {
      case Success(result) => result
      case Failure(e) =>
        logger.debug(s"DNS resolution failed for _atproto.$handle: ${e.getMessage}")
        None
    }
  }

  /**
   * Resolves a handle via HTTPS well-known endpoint.
   * Fetches https://{handle}/.well-known/atproto-did
   */
  private def resolveHandleViaWellKnown(handle: String): Future[Option[String]] = {
    val url = s"https://$handle/.well-known/atproto-did"

    ws.url(url)
      .withRequestTimeout(timeout)
      .get()
      .map { response =>
        if (response.status == 200) {
          val did = response.body.trim
          if (did.startsWith("did:")) Some(did) else None
        } else {
          logger.debug(s"Well-known resolution failed for $handle: ${response.status}")
          None
        }
      }
      .recover {
        case e: Exception =>
          logger.debug(s"Well-known resolution error for $handle: ${e.getMessage}")
          None
      }
  }

  /**
   * Resolves a DID to its associated PDS endpoint URL.
   *
   * For did:plc - queries plc.directory
   * For did:web - fetches /.well-known/did.json from the domain
   *
   * @param did The Decentralized Identifier (DID) to resolve.
   * @return A Future containing the PDS URL if resolved, otherwise None.
   */
  def resolveDid(did: String): Future[Option[DidDocument]] = {
    if (did.startsWith("did:plc:")) {
      resolveDidPlc(did)
    } else if (did.startsWith("did:web:")) {
      resolveDidWeb(did)
    } else {
      logger.warn(s"Unsupported DID method: $did")
      Future.successful(None)
    }
  }

  /**
   * Resolves a did:plc identifier via the PLC directory.
   */
  private def resolveDidPlc(did: String): Future[Option[DidDocument]] = {
    val url = s"$plcDirectoryUrl/$did"

    ws.url(url)
      .withRequestTimeout(timeout)
      .get()
      .map { response =>
        if (response.status == 200) {
          Json.fromJson[DidDocument](response.json) match {
            case JsSuccess(doc, _) => Some(doc)
            case JsError(errors) =>
              logger.error(s"Failed to parse DID document for $did: $errors")
              None
          }
        } else {
          logger.warn(s"Failed to resolve $did from PLC directory: ${response.status}")
          None
        }
      }
      .recover {
        case e: Exception =>
          logger.error(s"Error resolving $did from PLC directory: ${e.getMessage}", e)
          None
      }
  }

  /**
   * Resolves a did:web identifier by fetching the DID document from the domain.
   * did:web:example.com → https://example.com/.well-known/did.json
   * did:web:example.com:path:to:doc → https://example.com/path/to/doc/did.json
   */
  private def resolveDidWeb(did: String): Future[Option[DidDocument]] = {
    val parts = did.stripPrefix("did:web:").split(":")
    val domain = java.net.URLDecoder.decode(parts.head, "UTF-8")
    val path = if (parts.length > 1) {
      "/" + parts.tail.map(p => java.net.URLDecoder.decode(p, "UTF-8")).mkString("/") + "/did.json"
    } else {
      "/.well-known/did.json"
    }

    val url = s"https://$domain$path"

    ws.url(url)
      .withRequestTimeout(timeout)
      .get()
      .map { response =>
        if (response.status == 200) {
          Json.fromJson[DidDocument](response.json) match {
            case JsSuccess(doc, _) => Some(doc)
            case JsError(errors) =>
              logger.error(s"Failed to parse DID document for $did: $errors")
              None
          }
        } else {
          logger.warn(s"Failed to resolve $did via did:web: ${response.status}")
          None
        }
      }
      .recover {
        case e: Exception =>
          logger.error(s"Error resolving $did via did:web: ${e.getMessage}", e)
          None
      }
  }

  /**
   * Convenience method: Resolves a handle all the way to its PDS URL.
   *
   * @param handle The handle to resolve
   * @return A Future containing (DID, PDS URL) if fully resolved
   */
  def resolveHandleToPds(handle: String): Future[Option[(String, String)]] = {
    resolveHandle(handle).flatMap {
      case Some(did) =>
        resolveDid(did).map {
          case Some(doc) =>
            doc.getPdsEndpoint.map(pds => (did, pds))
          case None => None
        }
      case None =>
        Future.successful(None)
    }
  }

  /**
   * Authenticates with a PDS and creates a session.
   *
   * @param identifier The handle or DID of the user.
   * @param password   The app password.
   * @param pdsUrl     The PDS URL (defaulting to bsky.social if not provided, though in reality we should resolve it).
   * @return A Future containing the session response if successful.
   */
  def createSession(identifier: String, password: String, pdsUrl: String = "https://bsky.social"): Future[Option[SessionResponse]] = {
    val url = s"$pdsUrl/xrpc/com.atproto.server.createSession"
    
    val body = Json.obj(
      "identifier" -> identifier,
      "password" -> password
    )

    ws.url(url)
      .withRequestTimeout(timeout)
      .post(body)
      .map { response =>
        if (response.status == 200) {
          Json.fromJson[SessionResponse](response.json) match {
            case JsSuccess(value, _) => Some(value)
            case JsError(errors) =>
              logger.error(s"Failed to parse createSession response: $errors")
              None
          }
        } else {
          logger.warn(s"Failed to create session for $identifier on $pdsUrl. Status: ${response.status}, Body: ${response.body}")
          None
        }
      }
      .recover {
        case e: Exception =>
          logger.error(s"Error calling createSession on $pdsUrl: ${e.getMessage}", e)
          None
      }
  }

  /**
   * Verifies a PDS and retrieves its latest commit information using the provided authentication token.
   *
   * @param pdsUrl    The base URL of the PDS.
   * @param repoDid   The DID of the repository on the PDS.
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
                                 seq: Long // The sequence number of the latest commit
                               )

object LatestCommitResponse {
  implicit val format: play.api.libs.json.Format[LatestCommitResponse] = Json.format[LatestCommitResponse]
}

case class SessionResponse(
                            did: String,
                            handle: String,
                            email: Option[String],
                            accessJwt: String,
                            refreshJwt: String
                          )

object SessionResponse {
  implicit val format: play.api.libs.json.Format[SessionResponse] = Json.format[SessionResponse]
}

/**
 * Represents a service endpoint in a DID document.
 */
case class DidService(
                       id: String,
                       `type`: String,
                       serviceEndpoint: String
                     )

object DidService {
  implicit val format: play.api.libs.json.Format[DidService] = Json.format[DidService]
}

/**
 * Represents a DID Document returned from PLC directory or did:web resolution.
 * Simplified to extract only what we need for PDS resolution.
 */
case class DidDocument(
                        id: String,
                        alsoKnownAs: Option[Seq[String]] = None,
                        service: Option[Seq[DidService]] = None
                      ) {
  /**
   * Extracts the PDS endpoint URL from the DID document.
   * Looks for a service with type "AtprotoPersonalDataServer".
   */
  def getPdsEndpoint: Option[String] = {
    service.flatMap { services =>
      services.find(_.`type` == "AtprotoPersonalDataServer").map(_.serviceEndpoint)
    }
  }

  /**
   * Extracts the handle from alsoKnownAs (format: "at://handle")
   */
  def getHandle: Option[String] = {
    alsoKnownAs.flatMap { aliases =>
      aliases.find(_.startsWith("at://")).map(_.stripPrefix("at://"))
    }
  }
}

object DidDocument {
  implicit val format: play.api.libs.json.Format[DidDocument] = Json.format[DidDocument]
}
