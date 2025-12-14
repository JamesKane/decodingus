package actors

import config.GenomicsConfig
import org.apache.pekko.actor.Actor
import play.api.Logging
import services.genomics.YBrowseVariantIngestionService

import java.io.{BufferedInputStream, FileOutputStream}
import java.net.{HttpURLConnection, URI}
import java.nio.file.Files
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object YBrowseVariantUpdateActor {
  case object RunUpdate
  case class UpdateResult(success: Boolean, variantsIngested: Int, message: String)
  private case class UpdateComplete(result: UpdateResult)
}

/**
 * Actor responsible for downloading and ingesting Y-DNA SNP data from YBrowse.
 *
 * This actor:
 * 1. Downloads the VCF file from ybrowse.org
 * 2. Stores it at the configured local path
 * 3. Triggers variant ingestion via YBrowseVariantIngestionService
 */
class YBrowseVariantUpdateActor @javax.inject.Inject()(
  genomicsConfig: GenomicsConfig,
  ingestionService: YBrowseVariantIngestionService
)(implicit ec: ExecutionContext) extends Actor with Logging {

  import YBrowseVariantUpdateActor.*
  import org.apache.pekko.actor.ActorRef

  // Idle state - ready to accept updates
  override def receive: Receive = idle

  private def idle: Receive = {
    case RunUpdate =>
      logger.info("YBrowseVariantUpdateActor: Starting YBrowse variant update")
      val senderRef = sender()

      // Switch to running state to reject concurrent requests
      context.become(running(senderRef))

      runUpdate().onComplete {
        case Success(result) =>
          logger.info(s"YBrowseVariantUpdateActor: Update completed - ${result.message}")
          self ! UpdateComplete(result)
        case Failure(ex) =>
          logger.error(s"YBrowseVariantUpdateActor: Update failed - ${ex.getMessage}", ex)
          self ! UpdateComplete(UpdateResult(success = false, variantsIngested = 0, s"Update failed: ${ex.getMessage}"))
      }
  }

  // Running state - reject new requests while update is in progress
  private def running(originalSender: ActorRef): Receive = {
    case RunUpdate =>
      logger.warn("YBrowseVariantUpdateActor: Update already in progress, rejecting request")
      sender() ! UpdateResult(success = false, variantsIngested = 0, "Update already in progress. Please wait for the current update to complete.")

    case UpdateComplete(result) =>
      originalSender ! result
      context.become(idle)
  }

  private def runUpdate(): Future[UpdateResult] = {
    Future {
      downloadGffFile()
    }.flatMap {
      case Success(_) =>
        logger.info("GFF file downloaded successfully, starting ingestion")
        ingestionService.ingestGff(genomicsConfig.ybrowseGffStoragePath).map { count =>
          UpdateResult(success = true, variantsIngested = count, s"Successfully ingested $count variants from GFF")
        }
      case Failure(ex) =>
        Future.successful(UpdateResult(success = false, variantsIngested = 0, s"Download failed: ${ex.getMessage}"))
    }
  }

  private def downloadGffFile(): Try[Unit] = Try {
    val url = URI.create(genomicsConfig.ybrowseGffUrl).toURL
    val targetFile = genomicsConfig.ybrowseGffStoragePath

    // Check for fresh local file (cache for 24 hours)
    val cacheDuration = 24 * 60 * 60 * 1000L // 24 hours in millis
    if (targetFile.exists() && (System.currentTimeMillis() - targetFile.lastModified() < cacheDuration)) {
      logger.info(s"Local GFF file is fresh (< 24 hours old), skipping download: ${targetFile.getAbsolutePath}")
    } else {
      // Ensure parent directory exists
      val parentDir = targetFile.getParentFile
      if (parentDir != null && !parentDir.exists()) {
        Files.createDirectories(parentDir.toPath)
        logger.info(s"Created directory: ${parentDir.getAbsolutePath}")
      }

      // Download to a temp file first, then rename (atomic operation)
      val tempFile = new java.io.File(targetFile.getAbsolutePath + ".tmp")

      logger.info(s"Downloading GFF from ${genomicsConfig.ybrowseGffUrl} to ${tempFile.getAbsolutePath}")

      val connection = url.openConnection().asInstanceOf[HttpURLConnection]
      connection.setRequestMethod("GET")
      connection.setConnectTimeout(30000) // 30 seconds
      connection.setReadTimeout(300000)   // 5 minutes for large file

      try {
        val responseCode = connection.getResponseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
          throw new RuntimeException(s"HTTP request failed with status $responseCode")
        }

        val inputStream = new BufferedInputStream(connection.getInputStream)
        val outputStream = new FileOutputStream(tempFile)

        try {
          val buffer = new Array[Byte](8192)
          var bytesRead = 0
          var totalBytes = 0L

          while ({ bytesRead = inputStream.read(buffer); bytesRead != -1 }) {
            outputStream.write(buffer, 0, bytesRead)
            totalBytes += bytesRead
          }

          logger.info(s"Downloaded $totalBytes bytes")
        } finally {
          inputStream.close()
          outputStream.close()
        }

        // Atomic rename
        if (targetFile.exists()) {
          targetFile.delete()
        }
        if (!tempFile.renameTo(targetFile)) {
          throw new RuntimeException(s"Failed to rename temp file to ${targetFile.getAbsolutePath}")
        }

        logger.info(s"GFF file saved to ${targetFile.getAbsolutePath}")
      } finally {
        connection.disconnect()
      }
    }
  }
}
