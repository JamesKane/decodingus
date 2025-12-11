package actors

import config.GenomicsConfig
import org.apache.pekko.actor.Actor
import play.api.Logging
import services.genomics.YBrowseVariantIngestionService

import java.io.{BufferedInputStream, BufferedReader, FileOutputStream, InputStreamReader}
import java.net.{HttpURLConnection, URI}
import java.nio.file.Files
import java.util.zip.{GZIPInputStream, GZIPOutputStream}
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
      downloadVcfFile()
    }.flatMap {
      case Success(_) =>
        logger.info("VCF file downloaded successfully, sanitizing VCF")
        Future(sanitizeVcfFile()).flatMap {
          case Success(skipped) =>
            logger.info(s"VCF sanitized (removed $skipped malformed records), starting ingestion")
            ingestionService.ingestVcf(genomicsConfig.ybrowseVcfStoragePath).map { count =>
              UpdateResult(success = true, variantsIngested = count, s"Successfully ingested $count variants (skipped $skipped malformed records)")
            }
          case Failure(ex) =>
            Future.successful(UpdateResult(success = false, variantsIngested = 0, s"Sanitization failed: ${ex.getMessage}"))
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

  /**
   * Sanitizes the VCF file by removing malformed records that HTSJDK cannot parse.
   * Specifically filters out records with duplicate alleles (REF == ALT or duplicate ALT alleles).
   *
   * @return Try containing the number of skipped records
   */
  private def sanitizeVcfFile(): Try[Int] = Try {
    val sourceFile = genomicsConfig.ybrowseVcfStoragePath
    val tempFile = new java.io.File(sourceFile.getAbsolutePath + ".sanitized.tmp")

    logger.info(s"Sanitizing VCF file: ${sourceFile.getAbsolutePath}")

    val inputStream = new BufferedReader(
      new InputStreamReader(
        new GZIPInputStream(
          new BufferedInputStream(
            new java.io.FileInputStream(sourceFile)
          )
        )
      )
    )

    val outputStream = new java.io.PrintWriter(
      new java.io.OutputStreamWriter(
        new GZIPOutputStream(
          new FileOutputStream(tempFile)
        )
      )
    )

    var skippedCount = 0
    var lineNumber = 0

    try {
      var line: String = null
      while ({ line = inputStream.readLine(); line != null }) {
        lineNumber += 1
        if (line.startsWith("#")) {
          // Header line - pass through
          outputStream.println(line)
        } else {
          // Data line - check for duplicate alleles
          if (isValidVcfDataLine(line)) {
            outputStream.println(line)
          } else {
            skippedCount += 1
            if (skippedCount <= 10) {
              logger.warn(s"Skipping malformed VCF record at line $lineNumber: ${line.take(100)}...")
            }
          }
        }
      }

      if (skippedCount > 10) {
        logger.warn(s"Skipped ${skippedCount - 10} additional malformed records (warnings suppressed)")
      }
    } finally {
      inputStream.close()
      outputStream.close()
    }

    // Replace original with sanitized version
    if (sourceFile.exists()) {
      sourceFile.delete()
    }
    if (!tempFile.renameTo(sourceFile)) {
      throw new RuntimeException(s"Failed to rename sanitized file to ${sourceFile.getAbsolutePath}")
    }

    logger.info(s"VCF sanitization complete. Processed $lineNumber lines, skipped $skippedCount malformed records.")
    skippedCount
  }

  /**
   * Validates a VCF data line for common issues that break HTSJDK parsing.
   * Checks for:
   * - Duplicate alleles (REF appearing in ALT, or duplicate ALT alleles)
   * - Empty required fields
   */
  private def isValidVcfDataLine(line: String): Boolean = {
    val fields = line.split("\t", 6) // Only need first 5 fields: CHROM, POS, ID, REF, ALT
    if (fields.length < 5) return false

    val ref = fields(3).toUpperCase
    val altField = fields(4)

    // Handle missing ALT (just ".")
    if (altField == ".") return true

    val alts = altField.split(",").map(_.toUpperCase)

    // Check for duplicate alleles
    val allAlleles = ref +: alts
    val uniqueAlleles = allAlleles.distinct

    // If we have fewer unique alleles than total, there are duplicates
    uniqueAlleles.length == allAlleles.length
  }
}
