package services

import jakarta.inject.{Inject, Singleton}
import play.api.Logging

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@Singleton
class EncryptionService @Inject()(
                                   secretsManagerService: CachedSecretsManagerService
                                 ) extends Logging {

  private val ALGORITHM = "AES"

  /**
   * Encrypts a plain text string using AES.
   *
   * @param plainText The text to encrypt.
   * @return Option[String] The encrypted string (Base64 encoded), or None if encryption fails or key is missing.
   */
  def encrypt(plainText: String): Option[String] = {
    secretsManagerService.getCachedUserEncryptionKey.flatMap { keyString =>
      try {
        val key = new SecretKeySpec(keyString.getBytes(StandardCharsets.UTF_8), ALGORITHM)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encryptedBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8))
        Some(Base64.getEncoder.encodeToString(encryptedBytes))
      } catch {
        case e: Exception =>
          logger.error("Failed to encrypt data", e)
          None
      }
    }
  }

  /**
   * Decrypts an encrypted string (Base64 encoded) using AES.
   *
   * @param encryptedText The encrypted string.
   * @return Option[String] The decrypted plain text, or None if decryption fails or key is missing.
   */
  def decrypt(encryptedText: String): Option[String] = {
    secretsManagerService.getCachedUserEncryptionKey.flatMap { keyString =>
      try {
        val key = new SecretKeySpec(keyString.getBytes(StandardCharsets.UTF_8), ALGORITHM)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, key)
        val decodedBytes = Base64.getDecoder.decode(encryptedText)
        val decryptedBytes = cipher.doFinal(decodedBytes)
        Some(new String(decryptedBytes, StandardCharsets.UTF_8))
      } catch {
        case e: Exception =>
          logger.error("Failed to decrypt data", e)
          None
      }
    }
  }
}
