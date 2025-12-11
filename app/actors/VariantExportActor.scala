package actors

import org.apache.pekko.actor.Actor
import play.api.Logging
import services.{ExportResult, VariantExportService}

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object VariantExportActor {
  case object RunExport
  private case class ExportComplete(result: ExportResult)
}

/**
 * Actor responsible for generating daily variant export files.
 *
 * This actor:
 * 1. Generates a gzipped JSONL file of all variants
 * 2. Writes metadata about the export
 * 3. Handles concurrent request protection
 */
class VariantExportActor @javax.inject.Inject()(
  variantExportService: VariantExportService
)(implicit ec: ExecutionContext) extends Actor with Logging {

  import VariantExportActor.*
  import org.apache.pekko.actor.ActorRef

  override def receive: Receive = idle

  private def idle: Receive = {
    case RunExport =>
      logger.info("VariantExportActor: Starting variant export generation")
      val senderRef = sender()

      context.become(running(senderRef))

      variantExportService.generateExport().onComplete {
        case Success(result) =>
          if (result.success) {
            logger.info(s"VariantExportActor: Export completed - ${result.variantCount} variants, ${result.fileSizeBytes / 1024 / 1024}MB")
          } else {
            logger.error(s"VariantExportActor: Export failed - ${result.error.getOrElse("Unknown error")}")
          }
          self ! ExportComplete(result)
        case Failure(ex) =>
          logger.error(s"VariantExportActor: Export failed unexpectedly - ${ex.getMessage}", ex)
          self ! ExportComplete(ExportResult(
            success = false,
            variantCount = 0,
            fileSizeBytes = 0,
            generationTimeMs = 0,
            error = Some(ex.getMessage)
          ))
      }
  }

  private def running(originalSender: ActorRef): Receive = {
    case RunExport =>
      logger.warn("VariantExportActor: Export already in progress, ignoring request")
      sender() ! ExportResult(
        success = false,
        variantCount = 0,
        fileSizeBytes = 0,
        generationTimeMs = 0,
        error = Some("Export already in progress")
      )

    case ExportComplete(result) =>
      originalSender ! result
      context.become(idle)
  }
}
