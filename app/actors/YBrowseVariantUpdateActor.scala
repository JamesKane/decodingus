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

  override def receive: Receive = {
    case RunUpdate =>
      logger.info("YBrowseVariantUpdateActor: Starting YBrowse variant update")
      val senderRef = sender()

      runUpdate().onComplete {
        case Success(result) =>
          logger.info(s"YBrowseVariantUpdateActor: Update completed - ${result.message}")
          senderRef ! result
        case Failure(ex) =>
          logger.error(s"YBrowseVariantUpdateActor: Update failed - ${ex.getMessage}", ex)
          senderRef ! UpdateResult(success = false, variantsIngested = 0, s"Update failed: ${ex.getMessage}")
      }
  }

  private def runUpdate(): Future[UpdateResult] = {
    Future {
      downloadVcfFile()
    }.flatMap {
      case Success(_) =>
        logger.info("VCF file downloaded successfully, starting ingestion")
        ingestionService.ingestVcf(genomicsConfig.ybrowseVcfStoragePath).map { count =>
          UpdateResult(success = true, variantsIngested = count, s"Successfully ingested $count variants")
        }
      case Failure(ex) =>
        Future.successful(UpdateResult(success = false, variantsIngested = 0, s"Download failed: ${ex.getMessage}"))
    }
  }

  private def downloadVcfFile(): Try[Unit] = Try {
    val url = URI.create(genomicsConfig.ybrowseVcfUrl).toURL
    val targetFile = genomicsConfig.ybrowseVcfStoragePath

    // Ensure parent directory exists
    val parentDir = targetFile.getParentFile
    if (parentDir != null && !parentDir.exists()) {
      Files.createDirectories(parentDir.toPath)
      logger.info(s"Created directory: ${parentDir.getAbsolutePath}")
    }

    // Download to a temp file first, then rename (atomic operation)
    val tempFile = new java.io.File(targetFile.getAbsolutePath + ".tmp")

    logger.info(s"Downloading VCF from ${genomicsConfig.ybrowseVcfUrl} to ${tempFile.getAbsolutePath}")

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

      logger.info(s"VCF file saved to ${targetFile.getAbsolutePath}")
    } finally {
      connection.disconnect()
    }
  }
}
