package services

import com.google.inject.Singleton
import config.AWSSecretsConfig
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest

import java.time.Instant
import javax.inject.Inject
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import scala.util.Try

/**
 * A service for managing and caching secrets from AWS Secrets Manager.
 *
 * This service retrieves secrets, such as API keys, from AWS Secrets Manager
 * and caches them locally for a specified duration to reduce the number of
 * external API calls. Cached secrets are automatically refreshed after they expire.
 *
 * @constructor Creates a new instance of CachedSecretsManagerService.
 * @param config Configuration object containing AWS region and secret name details.
 * @param ec     The implicit ExecutionContext for handling asynchronous operations.
 */
@Singleton
class CachedSecretsManagerService @Inject()(
                                             config: AWSSecretsConfig
                                           )(implicit ec: ExecutionContext) {

  private val client = SecretsManagerClient.builder()
    .region(config.region)
    .build()

  private val cache = TrieMap[String, (String, Instant)]()
  private val CacheDuration = 1.hour

  /**
   * Retrieves the cached API key if it exists and is not expired. If the cached key is expired
   * or unavailable, retrieves a new API key from the AWS Secrets Manager, caches it, and returns it.
   *
   * @return An `Option` containing the API key as a `String`, or `None` if the key could not be retrieved.
   */
  def getCachedApiKey: Option[String] = {
    cache.get(config.apiKeySecretName) match {
      case Some((key, expiry)) if expiry.isAfter(Instant.now) =>
        Some(key)
      case _ =>
        getApiKey.toOption.map { key =>
          cache.put(config.apiKeySecretName, (key, Instant.now.plusSeconds(CacheDuration.toSeconds)))
          key
        }
    }
  }

  private def getApiKey: Try[String] = {
    Try {
      val request = GetSecretValueRequest.builder()
        .secretId(config.apiKeySecretName)
        .build()

      val response = client.getSecretValue(request)
      response.secretString()
    }
  }
}