package actions

import helpers.ServiceSpec
import models.domain.pds.PdsNode
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{reset, when}
import play.api.libs.json.Json
import play.api.mvc.*
import play.api.mvc.Results.Ok
import play.api.test.{FakeRequest, Helpers}
import play.api.test.Helpers.*
import repositories.PdsNodeRepository
import services.PdsSignatureVerifier

import java.time.Instant
import scala.concurrent.Future

class PdsAuthActionSpec extends ServiceSpec {

  implicit val system: ActorSystem = ActorSystem("test")
  implicit val mat: Materializer = Materializer(system)

  val mockVerifier: PdsSignatureVerifier = mock[PdsSignatureVerifier]
  val mockNodeRepo: PdsNodeRepository = mock[PdsNodeRepository]
  val stubParser: BodyParsers.Default = mock[BodyParsers.Default]

  val testNode: PdsNode = PdsNode(
    id = Some(1),
    did = "did:plc:testnode",
    pdsUrl = "https://pds.test.example.com",
    softwareVersion = Some("0.1.0"),
    status = "ONLINE"
  )

  val validTimestamp: String = Instant.now().toString
  val validNonce: String = "test-nonce-123"
  val validSignature: String = "dGVzdC1zaWduYXR1cmU="

  override def beforeEach(): Unit = {
    reset(mockVerifier, mockNodeRepo)
  }

  override def afterEach(): Unit = {
    super.afterEach()
  }

  // Clean up actor system when tests complete
  def cleanup(): Unit = system.terminate()

  private def buildAction(): PdsAuthAction =
    new PdsAuthAction(stubParser, Helpers.stubControllerComponents(), mockVerifier, mockNodeRepo)

  private def buildRequest(
                             did: Option[String] = Some("did:plc:testnode"),
                             signature: Option[String] = Some(validSignature),
                             timestamp: Option[String] = Some(validTimestamp),
                             nonce: Option[String] = Some(validNonce)
                           ): FakeRequest[AnyContentAsEmpty.type] = {
    var headers = Seq.empty[(String, String)]
    did.foreach(d => headers :+= ("X-PDS-DID" -> d))
    signature.foreach(s => headers :+= ("X-PDS-Signature" -> s))
    timestamp.foreach(t => headers :+= ("X-PDS-Timestamp" -> t))
    nonce.foreach(n => headers :+= ("X-PDS-Nonce" -> n))
    FakeRequest("POST", "/api/pds/heartbeat").withHeaders(headers*)
  }

  private def successBlock(request: PdsAuthRequest[?]): Future[Result] =
    Future.successful(Ok(Json.obj("node" -> request.pdsNode.did)))

  "PdsAuthAction" should {

    "reject request missing DID header" in {
      val action = buildAction()
      val request = buildRequest(did = None)
      val result = action.invokeBlock(request, successBlock)
      status(result) mustBe UNAUTHORIZED
      (contentAsJson(result) \ "error").as[String] must include("Missing required authentication headers")
    }

    "reject request missing Signature header" in {
      val action = buildAction()
      val request = buildRequest(signature = None)
      val result = action.invokeBlock(request, successBlock)
      status(result) mustBe UNAUTHORIZED
    }

    "reject request missing Timestamp header" in {
      val action = buildAction()
      val request = buildRequest(timestamp = None)
      val result = action.invokeBlock(request, successBlock)
      status(result) mustBe UNAUTHORIZED
    }

    "reject request with expired timestamp" in {
      val action = buildAction()
      when(mockVerifier.isTimestampValid(any[String])).thenReturn(false)

      val request = buildRequest()
      val result = action.invokeBlock(request, successBlock)
      status(result) mustBe UNAUTHORIZED
      (contentAsJson(result) \ "error").as[String] must include("timestamp")
    }

    "reject request with duplicate nonce" in {
      val action = buildAction()
      when(mockVerifier.isTimestampValid(any[String])).thenReturn(true)
      when(mockVerifier.checkAndRecordNonce(any[String])).thenReturn(false)

      val request = buildRequest()
      val result = action.invokeBlock(request, successBlock)
      status(result) mustBe UNAUTHORIZED
      (contentAsJson(result) \ "error").as[String] must include("Nonce")
    }

    "reject request from unregistered PDS node" in {
      val action = buildAction()
      when(mockVerifier.isTimestampValid(any[String])).thenReturn(true)
      when(mockVerifier.checkAndRecordNonce(any[String])).thenReturn(true)
      when(mockNodeRepo.findByDid("did:plc:testnode")).thenReturn(Future.successful(None))

      val request = buildRequest()
      val result = action.invokeBlock(request, successBlock)
      status(result) mustBe UNAUTHORIZED
      (contentAsJson(result) \ "error").as[String] must include("not registered")
    }

    "reject request with invalid signature" in {
      val action = buildAction()
      when(mockVerifier.isTimestampValid(any[String])).thenReturn(true)
      when(mockVerifier.checkAndRecordNonce(any[String])).thenReturn(true)
      when(mockNodeRepo.findByDid("did:plc:testnode")).thenReturn(Future.successful(Some(testNode)))
      when(mockVerifier.hashBody(any())).thenReturn("body-hash")
      when(mockVerifier.buildSigningInput(any[String], any[String], any[String], any[String], any()))
        .thenReturn("signing-input")
      when(mockVerifier.verifySignature(any[String], any[String], any[String]))
        .thenReturn(Future.successful(false))

      val request = buildRequest()
      val result = action.invokeBlock(request, successBlock)
      status(result) mustBe UNAUTHORIZED
      (contentAsJson(result) \ "error").as[String] must include("Invalid signature")
    }

    "allow request with valid signature and registered node" in {
      val action = buildAction()
      when(mockVerifier.isTimestampValid(any[String])).thenReturn(true)
      when(mockVerifier.checkAndRecordNonce(any[String])).thenReturn(true)
      when(mockNodeRepo.findByDid("did:plc:testnode")).thenReturn(Future.successful(Some(testNode)))
      when(mockVerifier.hashBody(any())).thenReturn("body-hash")
      when(mockVerifier.buildSigningInput(any[String], any[String], any[String], any[String], any()))
        .thenReturn("signing-input")
      when(mockVerifier.verifySignature(any[String], any[String], any[String]))
        .thenReturn(Future.successful(true))

      val request = buildRequest()
      val result = action.invokeBlock(request, successBlock)
      status(result) mustBe OK
      (contentAsJson(result) \ "node").as[String] mustBe "did:plc:testnode"
    }

    "pass the authenticated PDS node to the block" in {
      val action = buildAction()
      when(mockVerifier.isTimestampValid(any[String])).thenReturn(true)
      when(mockVerifier.checkAndRecordNonce(any[String])).thenReturn(true)
      when(mockNodeRepo.findByDid("did:plc:testnode")).thenReturn(Future.successful(Some(testNode)))
      when(mockVerifier.hashBody(any())).thenReturn("body-hash")
      when(mockVerifier.buildSigningInput(any[String], any[String], any[String], any[String], any()))
        .thenReturn("signing-input")
      when(mockVerifier.verifySignature(any[String], any[String], any[String]))
        .thenReturn(Future.successful(true))

      var capturedNode: Option[PdsNode] = None
      val captureBlock: PdsAuthRequest[?] => Future[Result] = { req =>
        capturedNode = Some(req.pdsNode)
        Future.successful(Ok)
      }

      val request = buildRequest()
      action.invokeBlock(request, captureBlock).futureValue

      capturedNode mustBe defined
      capturedNode.get.did mustBe "did:plc:testnode"
      capturedNode.get.id mustBe Some(1)
    }

    "work without nonce header" in {
      val action = buildAction()
      when(mockVerifier.isTimestampValid(any[String])).thenReturn(true)
      when(mockNodeRepo.findByDid("did:plc:testnode")).thenReturn(Future.successful(Some(testNode)))
      when(mockVerifier.hashBody(any())).thenReturn("body-hash")
      when(mockVerifier.buildSigningInput(any[String], any[String], any[String], any[String], any()))
        .thenReturn("signing-input")
      when(mockVerifier.verifySignature(any[String], any[String], any[String]))
        .thenReturn(Future.successful(true))

      val request = buildRequest(nonce = None)
      val result = action.invokeBlock(request, successBlock)
      status(result) mustBe OK
    }

    "handle signature verification errors gracefully" in {
      val action = buildAction()
      when(mockVerifier.isTimestampValid(any[String])).thenReturn(true)
      when(mockVerifier.checkAndRecordNonce(any[String])).thenReturn(true)
      when(mockNodeRepo.findByDid("did:plc:testnode")).thenReturn(Future.successful(Some(testNode)))
      when(mockVerifier.hashBody(any())).thenReturn("body-hash")
      when(mockVerifier.buildSigningInput(any[String], any[String], any[String], any[String], any()))
        .thenReturn("signing-input")
      when(mockVerifier.verifySignature(any[String], any[String], any[String]))
        .thenReturn(Future.failed(new RuntimeException("DID resolution timeout")))

      val request = buildRequest()
      val result = action.invokeBlock(request, successBlock)
      status(result) mustBe INTERNAL_SERVER_ERROR
      (contentAsJson(result) \ "error").as[String] must include("Authentication error")
    }
  }
}
