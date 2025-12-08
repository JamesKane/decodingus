package config

import play.api.Configuration
import software.amazon.awssdk.regions.Region

import javax.inject.{Inject, Singleton}

@Singleton
class AWSSecretsConfig @Inject()(configuration: Configuration) {
  val region: Region = Region.of(configuration.get[String]("aws.region"))
  val apiKeySecretName: String = configuration.get[String]("aws.secrets.apiKey.name")
  val userEncryptionKeySecretName: String = configuration.get[String]("aws.secrets.userEncryptionKey.name")
}