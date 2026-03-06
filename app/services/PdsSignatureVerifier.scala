package services

import jakarta.inject.{Inject, Singleton}
import play.api.{Configuration, Logging}
import utils.Base58

import java.nio.charset.StandardCharsets
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.time.{Duration, Instant}
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PdsSignatureVerifier @Inject()(
                                      atProtoClient: ATProtocolClient,
                                      configuration: Configuration
                                    )(implicit ec: ExecutionContext) extends Logging {

  private val timestampWindowSeconds: Long =
    configuration.getOptional[Long]("pds.auth.timestamp.window.seconds").getOrElse(300)

  private val usedNonces = new ConcurrentHashMap[String, Instant]()

  def isTimestampValid(timestamp: String): Boolean = {
    try {
      val requestTime = Instant.parse(timestamp)
      val now = Instant.now()
      Duration.between(requestTime, now).abs().getSeconds <= timestampWindowSeconds
    } catch {
      case _: Exception => false
    }
  }

  def checkAndRecordNonce(nonce: String): Boolean = {
    pruneExpiredNonces()
    usedNonces.putIfAbsent(nonce, Instant.now()) == null
  }

  def hashBody[A](request: play.api.mvc.Request[A]): String = {
    val bodyBytes = request.body match {
      case json: play.api.libs.json.JsValue =>
        json.toString.getBytes(StandardCharsets.UTF_8)
      case anyContent: play.api.mvc.AnyContent =>
        anyContent.asJson.map(_.toString.getBytes(StandardCharsets.UTF_8))
          .orElse(anyContent.asRaw.flatMap(_.asBytes().map(_.toArray)))
          .orElse(anyContent.asText.map(_.getBytes(StandardCharsets.UTF_8)))
          .getOrElse(Array.empty[Byte])
      case text: String =>
        text.getBytes(StandardCharsets.UTF_8)
      case _ => Array.empty[Byte]
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(bodyBytes)
    Base64.getEncoder.encodeToString(digest)
  }

  def buildSigningInput(
                         method: String,
                         path: String,
                         timestamp: String,
                         bodyHash: String,
                         nonce: Option[String]
                       ): String = {
    val parts = Seq(method.toUpperCase, path, timestamp, bodyHash) ++ nonce.toSeq
    parts.mkString("\n")
  }

  def verifySignature(did: String, signingInput: String, signatureBase64: String): Future[Boolean] = {
    atProtoClient.resolveDid(did).map {
      case None =>
        logger.warn(s"Could not resolve DID document for $did")
        false
      case Some(doc) =>
        doc.verificationMethod.flatMap(_.headOption) match {
          case None =>
            logger.warn(s"No verification method found in DID document for $did")
            false
          case Some(vm) =>
            vm.publicKeyMultibase match {
              case None =>
                logger.warn(s"No publicKeyMultibase in verification method for $did")
                false
              case Some(multibaseKey) =>
                try {
                  val publicKey = parseMultibaseKey(multibaseKey)
                  val signatureBytes = Base64.getDecoder.decode(signatureBase64)
                  val inputBytes = signingInput.getBytes(StandardCharsets.UTF_8)
                  verifyWithKey(publicKey, inputBytes, signatureBytes)
                } catch {
                  case e: Exception =>
                    logger.error(s"Signature verification failed for $did: ${e.getMessage}", e)
                    false
                }
            }
        }
    }
  }

  private[services] def parseMultibaseKey(multibaseKey: String): PublicKey = {
    require(multibaseKey.startsWith("z"), "Only base58btc (z prefix) multibase keys are supported")

    val decoded = Base58.decode(multibaseKey.substring(1))
    require(decoded.length >= 2, "Key too short to contain multicodec prefix")

    val (prefix0, prefix1) = (decoded(0) & 0xff, decoded(1) & 0xff)
    val rawKey = decoded.drop(2)

    (prefix0, prefix1) match {
      case (0xed, 0x01) => buildEd25519PublicKey(rawKey)
      case (0x80, 0x24) => buildP256PublicKey(rawKey)
      case _ =>
        throw new IllegalArgumentException(
          f"Unsupported multicodec key type: 0x${prefix0}%02x 0x${prefix1}%02x")
    }
  }

  private[services] def buildEd25519PublicKey(rawKey: Array[Byte]): PublicKey = {
    require(rawKey.length == 32, s"Ed25519 public key must be 32 bytes, got ${rawKey.length}")
    // ASN.1 DER prefix for Ed25519 public key (OID 1.3.101.112)
    val derPrefix: Array[Byte] = Array(
      0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
      0x70, 0x03, 0x21, 0x00
    ).map(_.toByte)
    val encoded = derPrefix ++ rawKey
    val keyFactory = KeyFactory.getInstance("Ed25519")
    keyFactory.generatePublic(new X509EncodedKeySpec(encoded))
  }

  private[services] def buildP256PublicKey(rawKey: Array[Byte]): PublicKey = {
    val derPrefix: Array[Byte] = if (rawKey.length == 33) {
      // Compressed P-256 point
      Array(
        0x30, 0x39, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86,
        0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a,
        0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03,
        0x22, 0x00
      ).map(_.toByte)
    } else if (rawKey.length == 65) {
      // Uncompressed P-256 point
      Array(
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86,
        0x48, 0xce, 0x3d, 0x02, 0x01, 0x06, 0x08, 0x2a,
        0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03,
        0x42, 0x00
      ).map(_.toByte)
    } else {
      throw new IllegalArgumentException(s"Invalid P-256 key length: ${rawKey.length}")
    }
    val encoded = derPrefix ++ rawKey
    val keyFactory = KeyFactory.getInstance("EC")
    keyFactory.generatePublic(new X509EncodedKeySpec(encoded))
  }

  private[services] def verifyWithKey(publicKey: PublicKey, data: Array[Byte], signature: Array[Byte]): Boolean = {
    val algorithm = publicKey.getAlgorithm match {
      case "Ed25519" | "EdDSA" => "Ed25519"
      case "EC" => "SHA256withECDSA"
      case other => throw new IllegalArgumentException(s"Unsupported key algorithm: $other")
    }
    val sig = Signature.getInstance(algorithm)
    sig.initVerify(publicKey)
    sig.update(data)
    sig.verify(signature)
  }

  private def pruneExpiredNonces(): Unit = {
    val cutoff = Instant.now().minusSeconds(timestampWindowSeconds * 2)
    usedNonces.entrySet().removeIf(e => e.getValue.isBefore(cutoff))
  }
}
