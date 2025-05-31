package services

import com.google.inject.Singleton
import config.AWSSecretsConfig
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest
import play.api.libs.json.Json

import javax.inject.Inject
import scala.util.Try
import org.apache.pekko.actor.ActorSystem

import java.time.Instant
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

@Singleton
class CachedSecretsManagerService @Inject()(
                                             config: AWSSecretsConfig,
                                             actorSystem: ActorSystem
                                           )(implicit ec: ExecutionContext) {
  private val client = SecretsManagerClient.builder()
    .region(config.region)
    .build()


  private val cache = TrieMap[String, (String, Instant)]()
  private val CacheDuration = 15.minutes

  // Refresh cache periodically
  actorSystem.scheduler.scheduleWithFixedDelay(
    initialDelay = 0.seconds,
    delay = CacheDuration / 2
  ) { () => refreshCache() }

  private def refreshCache(): Unit = {
    getApiKey.foreach { key =>
      cache.put(config.apiKeySecretName, (key, Instant.now.plusSeconds(CacheDuration.toSeconds)))
    }
  }

  def getCachedApiKey: Option[String] = {
    cache.get(config.apiKeySecretName).collect {
      case (key, expiry) if expiry.isAfter(Instant.now) => key
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
