package services.firehose

import models.api.{ExternalBiosampleRequest, ProjectRequest}
import models.atmosphere.*
import play.api.libs.json.*

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
  implicit val reads: Reads[FirehoseAction] = Reads.of[String].map(FirehoseAction.valueOf)
  implicit val writes: Writes[FirehoseAction] = Writes.of[String].contramap(_.toString)
  implicit val format: Format[FirehoseAction] = Format(reads, writes)
}

// --- Legacy / Phase 1 Events (to be deprecated/migrated) ---

/**
 * Event for Citizen Biosample operations (Legacy/Phase 1).
 * Uses the monolithic ExternalBiosampleRequest payload.
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
 * Event for Project operations (Legacy/Phase 1).
 */
case class ProjectEvent(
                         atUri: String,
                         atCid: Option[String],
                         action: FirehoseAction,
                         payload: Option[ProjectRequest]
                       ) extends FirehoseEvent

object ProjectEvent {
  implicit val format: OFormat[ProjectEvent] = Json.format
}

object ProjectEventFactory {
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

// --- Atmosphere Lexicon Events (Phase 3) ---

case class BiosampleEvent(
                           atUri: String,
                           atCid: Option[String],
                           action: FirehoseAction,
                           payload: Option[BiosampleRecord]
                         ) extends FirehoseEvent

object BiosampleEvent {
  implicit val format: OFormat[BiosampleEvent] = Json.format
}

case class SequenceRunEvent(
                             atUri: String,
                             atCid: Option[String],
                             action: FirehoseAction,
                             payload: Option[SequenceRunRecord]
                           ) extends FirehoseEvent

object SequenceRunEvent {
  implicit val format: OFormat[SequenceRunEvent] = Json.format
}

case class AlignmentEvent(
                           atUri: String,
                           atCid: Option[String],
                           action: FirehoseAction,
                           payload: Option[AlignmentRecord]
                         ) extends FirehoseEvent

object AlignmentEvent {
  implicit val format: OFormat[AlignmentEvent] = Json.format
}

case class GenotypeEvent(
                          atUri: String,
                          atCid: Option[String],
                          action: FirehoseAction,
                          payload: Option[GenotypeRecord]
                        ) extends FirehoseEvent

object GenotypeEvent {
  implicit val format: OFormat[GenotypeEvent] = Json.format
}

case class ImputationEvent(
                            atUri: String,
                            atCid: Option[String],
                            action: FirehoseAction,
                            payload: Option[ImputationRecord]
                          ) extends FirehoseEvent

object ImputationEvent {
  implicit val format: OFormat[ImputationEvent] = Json.format
}

case class AtmosphereProjectEvent(
                                   atUri: String,
                                   atCid: Option[String],
                                   action: FirehoseAction,
                                   payload: Option[ProjectRecord]
                                 ) extends FirehoseEvent

object AtmosphereProjectEvent {
  implicit val format: OFormat[AtmosphereProjectEvent] = Json.format
}

case class PopulationBreakdownEvent(
                                     atUri: String,
                                     atCid: Option[String],
                                     action: FirehoseAction,
                                     payload: Option[PopulationBreakdownRecord]
                                   ) extends FirehoseEvent

object PopulationBreakdownEvent {
  implicit val format: OFormat[PopulationBreakdownEvent] = Json.format
}

case class InstrumentObservationEvent(
                                       atUri: String,
                                       atCid: Option[String],
                                       action: FirehoseAction,
                                       payload: Option[InstrumentObservationRecord]
                                     ) extends FirehoseEvent

object InstrumentObservationEvent {
  implicit val format: OFormat[InstrumentObservationEvent] = Json.format
}

case class MatchConsentEvent(
                              atUri: String,
                              atCid: Option[String],
                              action: FirehoseAction,
                              payload: Option[MatchConsentRecord]
                            ) extends FirehoseEvent

object MatchConsentEvent {
  implicit val format: OFormat[MatchConsentEvent] = Json.format
}

case class MatchListEvent(
                           atUri: String,
                           atCid: Option[String],
                           action: FirehoseAction,
                           payload: Option[MatchListRecord]
                         ) extends FirehoseEvent

object MatchListEvent {
  implicit val format: OFormat[MatchListEvent] = Json.format
}

case class MatchRequestEvent(
                              atUri: String,
                              atCid: Option[String],
                              action: FirehoseAction,
                              payload: Option[MatchRequestRecord]
                            ) extends FirehoseEvent

object MatchRequestEvent {
  implicit val format: OFormat[MatchRequestEvent] = Json.format
}

case class StrProfileEvent(
                            atUri: String,
                            atCid: Option[String],
                            action: FirehoseAction,
                            payload: Option[StrProfileRecord]
                          ) extends FirehoseEvent

object StrProfileEvent {
  implicit val format: OFormat[StrProfileEvent] = Json.format
}

case class HaplogroupAncestralStrEvent(
                                        atUri: String,
                                        atCid: Option[String],
                                        action: FirehoseAction,
                                        payload: Option[HaplogroupAncestralStrRecord]
                                      ) extends FirehoseEvent

object HaplogroupAncestralStrEvent {
  implicit val format: OFormat[HaplogroupAncestralStrEvent] = Json.format
}

case class WorkspaceEvent(
                           atUri: String,
                           atCid: Option[String],
                           action: FirehoseAction,
                           payload: Option[WorkspaceRecord]
                         ) extends FirehoseEvent

object WorkspaceEvent {
  implicit val format: OFormat[WorkspaceEvent] = Json.format
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
