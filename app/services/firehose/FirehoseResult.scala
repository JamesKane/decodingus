package services.firehose

import java.util.UUID

/**
 * Result of processing a FirehoseEvent.
 * Provides a consistent result type regardless of the event source.
 */
sealed trait FirehoseResult {
  def atUri: String
}

object FirehoseResult {
  case class Success(
                      atUri: String,
                      newAtCid: String,
                      sampleGuid: Option[UUID] = None,
                      message: String = "OK"
                    ) extends FirehoseResult

  case class NotFound(atUri: String) extends FirehoseResult

  case class Conflict(atUri: String, message: String) extends FirehoseResult

  case class ValidationError(atUri: String, message: String) extends FirehoseResult

  case class Error(atUri: String, message: String, cause: Option[Throwable] = None) extends FirehoseResult
}
