package services.firehose

import models.api.{ExternalBiosampleRequest, ProjectRequest}
import play.api.libs.json.{Format, Json, OFormat}

/**
 * Represents events from the AT Protocol Firehose (or simulated via REST API).
 *
 * This abstraction allows the same event processing logic to be used whether
 * events arrive via:
 * - Phase 1: Direct REST API calls (wrapped as events)
 * - Phase 2: Kafka consumer
 * - Phase 3: AT Protocol Firehose subscription
 *
 * Each event includes:
 * - `atUri`: The canonical AT Protocol identifier for the record
 * - `atCid`: Content identifier for optimistic locking / version tracking
 * - `action`: The operation type (Create, Update, Delete)
 */
sealed trait FirehoseEvent {
  def atUri: String
  def atCid: Option[String]
  def action: FirehoseAction
}

/**
 * Actions that can be performed on a record.
 * Maps to AT Protocol commit operations.
 */
enum FirehoseAction:
  case Create, Update, Delete

object FirehoseAction {
  import play.api.libs.json.{Reads, Writes}

  implicit val reads: Reads[FirehoseAction] = Reads.of[String].map(FirehoseAction.valueOf)
  implicit val writes: Writes[FirehoseAction] = Writes.of[String].contramap(_.toString)
  implicit val format: Format[FirehoseAction] = Format(reads, writes)
}

/**
 * Event for Citizen Biosample operations.
 *
 * @param atUri The AT Protocol URI (at://did/collection/rkey)
 * @param atCid Content identifier for versioning
 * @param action The operation type
 * @param payload The biosample data (None for Delete operations)
 */
case class CitizenBiosampleEvent(
  atUri: String,
  atCid: Option[String],
  action: FirehoseAction,
  payload: Option[ExternalBiosampleRequest]
) extends FirehoseEvent

object CitizenBiosampleEvent {
  implicit val format: OFormat[CitizenBiosampleEvent] = Json.format

  def forCreate(request: ExternalBiosampleRequest): CitizenBiosampleEvent =
    CitizenBiosampleEvent(
      atUri = request.atUri.getOrElse(throw new IllegalArgumentException("atUri required for create")),
      atCid = request.atCid,
      action = FirehoseAction.Create,
      payload = Some(request)
    )

  def forUpdate(atUri: String, request: ExternalBiosampleRequest): CitizenBiosampleEvent =
    CitizenBiosampleEvent(
      atUri = atUri,
      atCid = request.atCid,
      action = FirehoseAction.Update,
      payload = Some(request)
    )

  def forDelete(atUri: String): CitizenBiosampleEvent =
    CitizenBiosampleEvent(
      atUri = atUri,
      atCid = None,
      action = FirehoseAction.Delete,
      payload = None
    )
}

/**
 * Event for Project operations.
 */
case class ProjectEvent(
  atUri: String,
  atCid: Option[String],
  action: FirehoseAction,
  payload: Option[ProjectRequest]
) extends FirehoseEvent

object ProjectEvent {
  implicit val format: OFormat[ProjectEvent] = Json.format

  def forCreate(atUri: String, request: ProjectRequest): ProjectEvent =
    ProjectEvent(
      atUri = atUri,
      atCid = request.atCid,
      action = FirehoseAction.Create,
      payload = Some(request)
    )

  def forUpdate(atUri: String, request: ProjectRequest): ProjectEvent =
    ProjectEvent(
      atUri = atUri,
      atCid = request.atCid,
      action = FirehoseAction.Update,
      payload = Some(request)
    )

  def forDelete(atUri: String): ProjectEvent =
    ProjectEvent(
      atUri = atUri,
      atCid = None,
      action = FirehoseAction.Delete,
      payload = None
    )
}

/**
 * Result of processing a FirehoseEvent.
 * Provides a consistent result type regardless of the event source.
 */
sealed trait FirehoseResult {
  def atUri: String
}

object FirehoseResult {
  import java.util.UUID

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
