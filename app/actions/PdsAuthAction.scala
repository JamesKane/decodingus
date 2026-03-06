package actions

import jakarta.inject.{Inject, Singleton}
import models.domain.pds.PdsNode
import org.apache.pekko.stream.Materializer
import play.api.Logging
import play.api.libs.json.{Json, Reads}
import play.api.mvc.*
import repositories.PdsNodeRepository
import services.PdsSignatureVerifier

import scala.concurrent.{ExecutionContext, Future}

case class PdsAuthRequest[A](pdsNode: PdsNode, request: Request[A]) extends WrappedRequest[A](request)

@Singleton
class PdsAuthAction @Inject()(
                                val parser: BodyParsers.Default,
                                val controllerComponents: ControllerComponents,
                                signatureVerifier: PdsSignatureVerifier,
                                nodeRepo: PdsNodeRepository
                              )(implicit val executionContext: ExecutionContext, val materializer: Materializer)
  extends ActionBuilder[PdsAuthRequest, AnyContent] with JsonValidation with Logging {

  private val DidHeader = "X-PDS-DID"
  private val SignatureHeader = "X-PDS-Signature"
  private val TimestampHeader = "X-PDS-Timestamp"
  private val NonceHeader = "X-PDS-Nonce"

  def jsonAction[A](implicit reader: Reads[A]): ActionBuilder[PdsAuthRequest, A] = {
    new ActionBuilder[PdsAuthRequest, A] {
      override def parser: BodyParser[A] = jsonBodyParser[A]

      override protected def executionContext: ExecutionContext = PdsAuthAction.this.executionContext

      override def invokeBlock[B](request: Request[B], block: PdsAuthRequest[B] => Future[Result]): Future[Result] = {
        PdsAuthAction.this.invokeBlock(request, block)
      }
    }
  }

  override def invokeBlock[A](request: Request[A], block: PdsAuthRequest[A] => Future[Result]): Future[Result] = {
    val headers = request.headers

    (headers.get(DidHeader), headers.get(SignatureHeader), headers.get(TimestampHeader)) match {
      case (Some(did), Some(signature), Some(timestamp)) =>
        authenticateRequest(request, did, signature, timestamp, headers.get(NonceHeader), block)
      case _ =>
        Future.successful(Results.Unauthorized(
          Json.obj("error" -> "Missing required authentication headers (X-PDS-DID, X-PDS-Signature, X-PDS-Timestamp)")
        ))
    }
  }

  private def authenticateRequest[A](
                                      request: Request[A],
                                      did: String,
                                      signature: String,
                                      timestamp: String,
                                      nonce: Option[String],
                                      block: PdsAuthRequest[A] => Future[Result]
                                    ): Future[Result] = {
    if (!signatureVerifier.isTimestampValid(timestamp)) {
      return Future.successful(Results.Unauthorized(
        Json.obj("error" -> "Request timestamp expired or invalid")
      ))
    }

    if (nonce.exists(n => !signatureVerifier.checkAndRecordNonce(n))) {
      return Future.successful(Results.Unauthorized(
        Json.obj("error" -> "Nonce already used")
      ))
    }

    nodeRepo.findByDid(did).flatMap {
      case None =>
        Future.successful(Results.Unauthorized(
          Json.obj("error" -> s"PDS node not registered: $did")
        ))
      case Some(node) =>
        val bodyHash = signatureVerifier.hashBody(request)
        val signingInput = signatureVerifier.buildSigningInput(
          request.method, request.path, timestamp, bodyHash, nonce
        )

        signatureVerifier.verifySignature(did, signingInput, signature).flatMap {
          case true =>
            logger.debug(s"PDS request authenticated for $did")
            block(PdsAuthRequest(node, request))
          case false =>
            Future.successful(Results.Unauthorized(
              Json.obj("error" -> "Invalid signature")
            ))
        }.recover {
          case e: Exception =>
            logger.error(s"Error during PDS authentication for $did: ${e.getMessage}", e)
            Results.InternalServerError(Json.obj("error" -> "Authentication error"))
        }
    }
  }
}
