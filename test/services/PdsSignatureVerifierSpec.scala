package services

import helpers.ServiceSpec
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{reset, when}
import play.api.{Configuration, Logging}
import play.api.libs.json.Json
import play.api.mvc.AnyContentAsJson
import play.api.test.FakeRequest
import utils.Base58

import java.nio.charset.StandardCharsets
import java.security.*
import java.time.Instant
import java.util.Base64
import scala.concurrent.Future

class PdsSignatureVerifierSpec extends ServiceSpec with Logging {

  val mockAtProtoClient: ATProtocolClient = mock[ATProtocolClient]
  val config: Configuration = Configuration.from(Map(
    "pds.auth.timestamp.window.seconds" -> 300
  ))

  val verifier = new PdsSignatureVerifier(mockAtProtoClient, config)

  override def beforeEach(): Unit = {
    reset(mockAtProtoClient)
  }

  // Generate a test Ed25519 key pair
  private def generateEd25519KeyPair(): KeyPair = {
    val kpg = KeyPairGenerator.getInstance("Ed25519")
    kpg.generateKeyPair()
  }

  // Generate a test P-256 key pair
  private def generateP256KeyPair(): KeyPair = {
    val kpg = KeyPairGenerator.getInstance("EC")
    kpg.initialize(256)
    kpg.generateKeyPair()
  }

  // Sign data with a private key
  private def sign(privateKey: PrivateKey, data: Array[Byte]): Array[Byte] = {
    val algorithm = privateKey.getAlgorithm match {
      case "Ed25519" | "EdDSA" => "Ed25519"
      case "EC" => "SHA256withECDSA"
    }
    val sig = Signature.getInstance(algorithm)
    sig.initSign(privateKey)
    sig.update(data)
    sig.sign()
  }

  // Extract raw Ed25519 public key bytes and encode as multibase
  private def ed25519ToMultibase(publicKey: PublicKey): String = {
    val encoded = publicKey.getEncoded
    // X.509 DER encoded Ed25519 public key: 12-byte prefix + 32-byte key
    val rawKey = encoded.drop(12)
    val multicodecKey = Array(0xed.toByte, 0x01.toByte) ++ rawKey
    "z" + Base58.encode(multicodecKey)
  }

  // Build a mock DidDocument with a verification method
  private def mockDidDocument(did: String, multibaseKey: String): DidDocument = {
    DidDocument(
      id = did,
      verificationMethod = Some(Seq(
        DidVerificationMethod(
          id = s"$did#atproto",
          `type` = "Multikey",
          controller = did,
          publicKeyMultibase = Some(multibaseKey)
        )
      )),
      service = Some(Seq(
        DidService(
          id = "#atproto_pds",
          `type` = "AtprotoPersonalDataServer",
          serviceEndpoint = "https://pds.example.com"
        )
      ))
    )
  }

  "PdsSignatureVerifier.isTimestampValid" should {

    "accept a current timestamp" in {
      verifier.isTimestampValid(Instant.now().toString) mustBe true
    }

    "accept a timestamp within the window" in {
      val twoMinutesAgo = Instant.now().minusSeconds(120).toString
      verifier.isTimestampValid(twoMinutesAgo) mustBe true
    }

    "reject an expired timestamp" in {
      val tenMinutesAgo = Instant.now().minusSeconds(600).toString
      verifier.isTimestampValid(tenMinutesAgo) mustBe false
    }

    "reject a future timestamp beyond window" in {
      val tenMinutesAhead = Instant.now().plusSeconds(600).toString
      verifier.isTimestampValid(tenMinutesAhead) mustBe false
    }

    "reject an invalid timestamp string" in {
      verifier.isTimestampValid("not-a-timestamp") mustBe false
    }
  }

  "PdsSignatureVerifier.checkAndRecordNonce" should {

    "accept a new nonce" in {
      verifier.checkAndRecordNonce("unique-nonce-1") mustBe true
    }

    "reject a duplicate nonce" in {
      val nonce = "duplicate-nonce-test"
      verifier.checkAndRecordNonce(nonce) mustBe true
      verifier.checkAndRecordNonce(nonce) mustBe false
    }
  }

  "PdsSignatureVerifier.buildSigningInput" should {

    "build signing input without nonce" in {
      val result = verifier.buildSigningInput("POST", "/api/heartbeat", "2026-01-01T00:00:00Z", "abc123", None)
      result mustBe "POST\n/api/heartbeat\n2026-01-01T00:00:00Z\nabc123"
    }

    "build signing input with nonce" in {
      val result = verifier.buildSigningInput("POST", "/api/heartbeat", "2026-01-01T00:00:00Z", "abc123", Some("nonce1"))
      result mustBe "POST\n/api/heartbeat\n2026-01-01T00:00:00Z\nabc123\nnonce1"
    }

    "uppercase the method" in {
      val result = verifier.buildSigningInput("post", "/api/test", "ts", "hash", None)
      result mustBe "POST\n/api/test\nts\nhash"
    }
  }

  "PdsSignatureVerifier.hashBody" should {

    "hash a JSON body" in {
      val body = Json.obj("key" -> "value")
      val request = FakeRequest("POST", "/test").withBody(body)
      val hash = verifier.hashBody(request)
      hash must not be empty
    }

    "produce consistent hashes for same content" in {
      val body = Json.obj("key" -> "value")
      val req1 = FakeRequest("POST", "/test").withBody(body)
      val req2 = FakeRequest("POST", "/test").withBody(body)
      verifier.hashBody(req1) mustBe verifier.hashBody(req2)
    }

    "produce different hashes for different content" in {
      val req1 = FakeRequest("POST", "/test").withBody(Json.obj("a" -> 1))
      val req2 = FakeRequest("POST", "/test").withBody(Json.obj("b" -> 2))
      verifier.hashBody(req1) must not be verifier.hashBody(req2)
    }
  }

  "PdsSignatureVerifier.parseMultibaseKey" should {

    "parse an Ed25519 multibase key" in {
      val kp = generateEd25519KeyPair()
      val multibase = ed25519ToMultibase(kp.getPublic)
      val parsed = verifier.parseMultibaseKey(multibase)
      parsed.getAlgorithm must (equal("Ed25519") or equal("EdDSA"))
    }

    "reject non-base58btc prefix" in {
      an[IllegalArgumentException] must be thrownBy {
        verifier.parseMultibaseKey("Minvalid")
      }
    }

    "reject unsupported multicodec prefix" in {
      val fakeKey = Array(0xff.toByte, 0xff.toByte) ++ Array.fill(32)(0.toByte)
      val multibase = "z" + Base58.encode(fakeKey)
      an[IllegalArgumentException] must be thrownBy {
        verifier.parseMultibaseKey(multibase)
      }
    }
  }

  "PdsSignatureVerifier.verifySignature" should {

    "verify a valid Ed25519 signature" in {
      val did = "did:plc:testuser1"
      val kp = generateEd25519KeyPair()
      val multibase = ed25519ToMultibase(kp.getPublic)

      when(mockAtProtoClient.resolveDid(did))
        .thenReturn(Future.successful(Some(mockDidDocument(did, multibase))))

      val signingInput = "POST\n/api/heartbeat\n2026-01-01T00:00:00Z\nhash123"
      val signature = sign(kp.getPrivate, signingInput.getBytes(StandardCharsets.UTF_8))
      val signatureBase64 = Base64.getEncoder.encodeToString(signature)

      val result = verifier.verifySignature(did, signingInput, signatureBase64).futureValue
      result mustBe true
    }

    "reject a signature with wrong data" in {
      val did = "did:plc:testuser2"
      val kp = generateEd25519KeyPair()
      val multibase = ed25519ToMultibase(kp.getPublic)

      when(mockAtProtoClient.resolveDid(did))
        .thenReturn(Future.successful(Some(mockDidDocument(did, multibase))))

      val signature = sign(kp.getPrivate, "correct-data".getBytes(StandardCharsets.UTF_8))
      val signatureBase64 = Base64.getEncoder.encodeToString(signature)

      val result = verifier.verifySignature(did, "wrong-data", signatureBase64).futureValue
      result mustBe false
    }

    "reject a signature from a different key" in {
      val did = "did:plc:testuser3"
      val kp1 = generateEd25519KeyPair()
      val kp2 = generateEd25519KeyPair()
      val multibase = ed25519ToMultibase(kp1.getPublic)

      when(mockAtProtoClient.resolveDid(did))
        .thenReturn(Future.successful(Some(mockDidDocument(did, multibase))))

      val signingInput = "POST\n/api/test\nts\nhash"
      val signature = sign(kp2.getPrivate, signingInput.getBytes(StandardCharsets.UTF_8))
      val signatureBase64 = Base64.getEncoder.encodeToString(signature)

      val result = verifier.verifySignature(did, signingInput, signatureBase64).futureValue
      result mustBe false
    }

    "return false when DID cannot be resolved" in {
      when(mockAtProtoClient.resolveDid("did:plc:unknown"))
        .thenReturn(Future.successful(None))

      val result = verifier.verifySignature("did:plc:unknown", "data", "sig").futureValue
      result mustBe false
    }

    "return false when DID document has no verification method" in {
      val did = "did:plc:nokeys"
      val doc = DidDocument(id = did, verificationMethod = None)

      when(mockAtProtoClient.resolveDid(did))
        .thenReturn(Future.successful(Some(doc)))

      val result = verifier.verifySignature(did, "data", "sig").futureValue
      result mustBe false
    }

    "return false when verification method has no publicKeyMultibase" in {
      val did = "did:plc:nokey"
      val doc = DidDocument(
        id = did,
        verificationMethod = Some(Seq(
          DidVerificationMethod(id = "#key", `type` = "Multikey", controller = did, publicKeyMultibase = None)
        ))
      )

      when(mockAtProtoClient.resolveDid(did))
        .thenReturn(Future.successful(Some(doc)))

      val result = verifier.verifySignature(did, "data", "sig").futureValue
      result mustBe false
    }
  }

  "PdsSignatureVerifier end-to-end" should {

    "verify a complete request signing flow" in {
      val did = "did:plc:e2e-test"
      val kp = generateEd25519KeyPair()
      val multibase = ed25519ToMultibase(kp.getPublic)

      when(mockAtProtoClient.resolveDid(did))
        .thenReturn(Future.successful(Some(mockDidDocument(did, multibase))))

      val body = Json.obj("status" -> "ONLINE", "softwareVersion" -> "0.1.0")
      val request = FakeRequest("POST", "/api/pds/heartbeat").withBody(body)
      val timestamp = Instant.now().toString
      val nonce = java.util.UUID.randomUUID().toString

      val bodyHash = verifier.hashBody(request)
      val signingInput = verifier.buildSigningInput("POST", "/api/pds/heartbeat", timestamp, bodyHash, Some(nonce))
      val signature = sign(kp.getPrivate, signingInput.getBytes(StandardCharsets.UTF_8))
      val signatureBase64 = Base64.getEncoder.encodeToString(signature)

      verifier.isTimestampValid(timestamp) mustBe true
      verifier.checkAndRecordNonce(nonce) mustBe true
      verifier.verifySignature(did, signingInput, signatureBase64).futureValue mustBe true
    }
  }
}
