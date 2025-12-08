package services.firehose

import models.api.{ExternalBiosampleRequest, ProjectRequest}
import models.atmosphere.*
import play.api.libs.json.*
import play.api.libs.functional.syntax._

/**
 * Represents events from the AT Protocol Firehose (or simulated via REST API).
 *
 * This abstraction allows the same event processing logic to be used whether
 * events arrive via:
 * - Phase 1: Direct REST API calls (wrapped as events)
 * - Phase 2: AT Protocol Firehose subscription
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

// --- Atmosphere Lexicon Events (Phase 3) ---

case class BiosampleEvent(
                           atUri: String,
                           atCid: Option[String],
                           action: FirehoseAction,
                           payload: Option[BiosampleRecord]
                         ) extends FirehoseEvent

object BiosampleEvent {
  implicit val format: OFormat[BiosampleEvent] = Json.format
  implicit val formatWithDiscriminator: OFormat[BiosampleEvent] = (
    (JsPath \ "atUri").format[String] and
      (JsPath \ "atCid").formatNullable[String] and
      (JsPath \ "action").format[FirehoseAction] and
      (JsPath \ "payload").formatNullable[BiosampleRecord]
    )(BiosampleEvent.apply, (e: BiosampleEvent) => (e.atUri, e.atCid, e.action, e.payload))
}

case class SequenceRunEvent(
                             atUri: String,
                             atCid: Option[String],
                             action: FirehoseAction,
                             payload: Option[SequenceRunRecord]
                           ) extends FirehoseEvent

object SequenceRunEvent {
  implicit val format: OFormat[SequenceRunEvent] = Json.format
  implicit val formatWithDiscriminator: OFormat[SequenceRunEvent] = (
    (JsPath \ "atUri").format[String] and
      (JsPath \ "atCid").formatNullable[String] and
      (JsPath \ "action").format[FirehoseAction] and
      (JsPath \ "payload").formatNullable[SequenceRunRecord]
    )(SequenceRunEvent.apply, (e: SequenceRunEvent) => (e.atUri, e.atCid, e.action, e.payload))
}

case class AlignmentEvent(
                           atUri: String,
                           atCid: Option[String],
                           action: FirehoseAction,
                           payload: Option[AlignmentRecord]
                         ) extends FirehoseEvent

object AlignmentEvent {
  implicit val format: OFormat[AlignmentEvent] = Json.format
  implicit val formatWithDiscriminator: OFormat[AlignmentEvent] = (
    (JsPath \ "atUri").format[String] and
      (JsPath \ "atCid").formatNullable[String] and
      (JsPath \ "action").format[FirehoseAction] and
      (JsPath \ "payload").formatNullable[AlignmentRecord]
    )(AlignmentEvent.apply, (e: AlignmentEvent) => (e.atUri, e.atCid, e.action, e.payload))
}

case class GenotypeEvent(
                          atUri: String,
                          atCid: Option[String],
                          action: FirehoseAction,
                          payload: Option[GenotypeRecord]
                        ) extends FirehoseEvent

object GenotypeEvent {
  implicit val format: OFormat[GenotypeEvent] = Json.format
  implicit val formatWithDiscriminator: OFormat[GenotypeEvent] = (
    (JsPath \ "atUri").format[String] and
      (JsPath \ "atCid").formatNullable[String] and
      (JsPath \ "action").format[FirehoseAction] and
      (JsPath \ "payload").formatNullable[GenotypeRecord]
    )(GenotypeEvent.apply, (e: GenotypeEvent) => (e.atUri, e.atCid, e.action, e.payload))
}

case class ImputationEvent(
                            atUri: String,
                            atCid: Option[String],
                            action: FirehoseAction,
                            payload: Option[ImputationRecord]
                          ) extends FirehoseEvent

object ImputationEvent {
  implicit val format: OFormat[ImputationEvent] = Json.format
  implicit val formatWithDiscriminator: OFormat[ImputationEvent] = (
    (JsPath \ "atUri").format[String] and
      (JsPath \ "atCid").formatNullable[String] and
      (JsPath \ "action").format[FirehoseAction] and
      (JsPath \ "payload").formatNullable[ImputationRecord]
    )(ImputationEvent.apply, (e: ImputationEvent) => (e.atUri, e.atCid, e.action, e.payload))
}

case class AtmosphereProjectEvent(
                                   atUri: String,
                                   atCid: Option[String],
                                   action: FirehoseAction,
                                   payload: Option[ProjectRecord]
                                 ) extends FirehoseEvent

object AtmosphereProjectEvent {
  implicit val format: OFormat[AtmosphereProjectEvent] = Json.format
  implicit val formatWithDiscriminator: OFormat[AtmosphereProjectEvent] = (
    (JsPath \ "atUri").format[String] and
      (JsPath \ "atCid").formatNullable[String] and
      (JsPath \ "action").format[FirehoseAction] and
      (JsPath \ "payload").formatNullable[ProjectRecord]
    )(AtmosphereProjectEvent.apply, (e: AtmosphereProjectEvent) => (e.atUri, e.atCid, e.action, e.payload))
}

case class PopulationBreakdownEvent(
                                     atUri: String,
                                     atCid: Option[String],
                                     action: FirehoseAction,
                                     payload: Option[PopulationBreakdownRecord]
                                   ) extends FirehoseEvent

object PopulationBreakdownEvent {
  implicit val format: OFormat[PopulationBreakdownEvent] = Json.format
  implicit val formatWithDiscriminator: OFormat[PopulationBreakdownEvent] = (
    (JsPath \ "atUri").format[String] and
      (JsPath \ "atCid").formatNullable[String] and
      (JsPath \ "action").format[FirehoseAction] and
      (JsPath \ "payload").formatNullable[PopulationBreakdownRecord]
    )(PopulationBreakdownEvent.apply, (e: PopulationBreakdownEvent) => (e.atUri, e.atCid, e.action, e.payload))
}

case class InstrumentObservationEvent(
                                       atUri: String,
                                       atCid: Option[String],
                                       action: FirehoseAction,
                                       payload: Option[InstrumentObservationRecord]
                                     ) extends FirehoseEvent

object InstrumentObservationEvent {
  implicit val format: OFormat[InstrumentObservationEvent] = Json.format
  implicit val formatWithDiscriminator: OFormat[InstrumentObservationEvent] = (
    (JsPath \ "atUri").format[String] and
      (JsPath \ "atCid").formatNullable[String] and
      (JsPath \ "action").format[FirehoseAction] and
      (JsPath \ "payload").formatNullable[InstrumentObservationRecord]
    )(InstrumentObservationEvent.apply, (e: InstrumentObservationEvent) => (e.atUri, e.atCid, e.action, e.payload))
}

case class MatchConsentEvent(
                              atUri: String,
                              atCid: Option[String],
                              action: FirehoseAction,
                              payload: Option[MatchConsentRecord]
                            ) extends FirehoseEvent

object MatchConsentEvent {
  implicit val format: OFormat[MatchConsentEvent] = Json.format
  implicit val formatWithDiscriminator: OFormat[MatchConsentEvent] = (
    (JsPath \ "atUri").format[String] and
      (JsPath \ "atCid").formatNullable[String] and
      (JsPath \ "action").format[FirehoseAction] and
      (JsPath \ "payload").formatNullable[MatchConsentRecord]
    )(MatchConsentEvent.apply, (e: MatchConsentEvent) => (e.atUri, e.atCid, e.action, e.payload))
}

case class MatchListEvent(
                           atUri: String,
                           atCid: Option[String],
                           action: FirehoseAction,
                           payload: Option[MatchListRecord]
                         ) extends FirehoseEvent

object MatchListEvent {
  implicit val format: OFormat[MatchListEvent] = Json.format
  implicit val formatWithDiscriminator: OFormat[MatchListEvent] = (
    (JsPath \ "atUri").format[String] and
      (JsPath \ "atCid").formatNullable[String] and
      (JsPath \ "action").format[FirehoseAction] and
      (JsPath \ "payload").formatNullable[MatchListRecord]
    )(MatchListEvent.apply, (e: MatchListEvent) => (e.atUri, e.atCid, e.action, e.payload))
}

case class MatchRequestEvent(
                              atUri: String,
                              atCid: Option[String],
                              action: FirehoseAction,
                              payload: Option[MatchRequestRecord]
                            ) extends FirehoseEvent

object MatchRequestEvent {
  implicit val format: OFormat[MatchRequestEvent] = Json.format
  implicit val formatWithDiscriminator: OFormat[MatchRequestEvent] = (
    (JsPath \ "atUri").format[String] and
      (JsPath \ "atCid").formatNullable[String] and
      (JsPath \ "action").format[FirehoseAction] and
      (JsPath \ "payload").formatNullable[MatchRequestRecord]
    )(MatchRequestEvent.apply, (e: MatchRequestEvent) => (e.atUri, e.atCid, e.action, e.payload))
}

case class StrProfileEvent(
                            atUri: String,
                            atCid: Option[String],
                            action: FirehoseAction,
                            payload: Option[StrProfileRecord]
                          ) extends FirehoseEvent

object StrProfileEvent {
  implicit val format: OFormat[StrProfileEvent] = Json.format
  implicit val formatWithDiscriminator: OFormat[StrProfileEvent] = (
    (JsPath \ "atUri").format[String] and
      (JsPath \ "atCid").formatNullable[String] and
      (JsPath \ "action").format[FirehoseAction] and
      (JsPath \ "payload").formatNullable[StrProfileRecord]
    )(StrProfileEvent.apply, (e: StrProfileEvent) => (e.atUri, e.atCid, e.action, e.payload))
}

case class HaplogroupAncestralStrEvent(
                                        atUri: String,
                                        atCid: Option[String],
                                        action: FirehoseAction,
                                        payload: Option[HaplogroupAncestralStrRecord]
                                      ) extends FirehoseEvent

object HaplogroupAncestralStrEvent {
  implicit val format: OFormat[HaplogroupAncestralStrEvent] = Json.format
  implicit val formatWithDiscriminator: OFormat[HaplogroupAncestralStrEvent] = (
    (JsPath \ "atUri").format[String] and
      (JsPath \ "atCid").formatNullable[String] and
      (JsPath \ "action").format[FirehoseAction] and
      (JsPath \ "payload").formatNullable[HaplogroupAncestralStrRecord]
    )(HaplogroupAncestralStrEvent.apply, (e: HaplogroupAncestralStrEvent) => (e.atUri, e.atCid, e.action, e.payload))
}

case class WorkspaceEvent(
                           atUri: String,
                           atCid: Option[String],
                           action: FirehoseAction,
                           payload: Option[WorkspaceRecord]
                         ) extends FirehoseEvent

object WorkspaceEvent {
  implicit val format: OFormat[WorkspaceEvent] = Json.format
  implicit val formatWithDiscriminator: OFormat[WorkspaceEvent] = (
    (JsPath \ "atUri").format[String] and
      (JsPath \ "atCid").formatNullable[String] and
      (JsPath \ "action").format[FirehoseAction] and
      (JsPath \ "payload").formatNullable[WorkspaceRecord]
    )(WorkspaceEvent.apply, (e: WorkspaceEvent) => (e.atUri, e.atCid, e.action, e.payload))
}

object FirehoseEvent {
  implicit val firehoseEventReads: Reads[FirehoseEvent] = new Reads[FirehoseEvent] {
    override def reads(json: JsValue): JsResult[FirehoseEvent] = {
      (json \ "_type").asOpt[String] match {
        case Some("BiosampleEvent") => json.validate[BiosampleEvent](BiosampleEvent.formatWithDiscriminator)
        case Some("SequenceRunEvent") => json.validate[SequenceRunEvent](SequenceRunEvent.formatWithDiscriminator)
        case Some("AlignmentEvent") => json.validate[AlignmentEvent](AlignmentEvent.formatWithDiscriminator)
        case Some("GenotypeEvent") => json.validate[GenotypeEvent](GenotypeEvent.formatWithDiscriminator)
        case Some("ImputationEvent") => json.validate[ImputationEvent](ImputationEvent.formatWithDiscriminator)
        case Some("AtmosphereProjectEvent") => json.validate[AtmosphereProjectEvent](AtmosphereProjectEvent.formatWithDiscriminator)
        case Some("PopulationBreakdownEvent") => json.validate[PopulationBreakdownEvent](PopulationBreakdownEvent.formatWithDiscriminator)
        case Some("InstrumentObservationEvent") => json.validate[InstrumentObservationEvent](InstrumentObservationEvent.formatWithDiscriminator)
        case Some("MatchConsentEvent") => json.validate[MatchConsentEvent](MatchConsentEvent.formatWithDiscriminator)
        case Some("MatchListEvent") => json.validate[MatchListEvent](MatchListEvent.formatWithDiscriminator)
        case Some("MatchRequestEvent") => json.validate[MatchRequestEvent](MatchRequestEvent.formatWithDiscriminator)
        case Some("StrProfileEvent") => json.validate[StrProfileEvent](StrProfileEvent.formatWithDiscriminator)
        case Some("HaplogroupAncestralStrEvent") => json.validate[HaplogroupAncestralStrEvent](HaplogroupAncestralStrEvent.formatWithDiscriminator)
        case Some("WorkspaceEvent") => json.validate[WorkspaceEvent](WorkspaceEvent.formatWithDiscriminator)
        case Some(unknown) => JsError(s"Unknown FirehoseEvent type: $unknown")
        case None => JsError("Missing '_type' discriminator field for FirehoseEvent")
      }
    }
  }

  implicit val firehoseEventWrites: Writes[FirehoseEvent] = Writes {
    case e: BiosampleEvent => Json.toJsObject(e)(BiosampleEvent.formatWithDiscriminator) + ("_type" -> JsString("BiosampleEvent"))
    case e: SequenceRunEvent => Json.toJsObject(e)(SequenceRunEvent.formatWithDiscriminator) + ("_type" -> JsString("SequenceRunEvent"))
    case e: AlignmentEvent => Json.toJsObject(e)(AlignmentEvent.formatWithDiscriminator) + ("_type" -> JsString("AlignmentEvent"))
    case e: GenotypeEvent => Json.toJsObject(e)(GenotypeEvent.formatWithDiscriminator) + ("_type" -> JsString("GenotypeEvent"))
    case e: ImputationEvent => Json.toJsObject(e)(ImputationEvent.formatWithDiscriminator) + ("_type" -> JsString("ImputationEvent"))
    case e: AtmosphereProjectEvent => Json.toJsObject(e)(AtmosphereProjectEvent.formatWithDiscriminator) + ("_type" -> JsString("AtmosphereProjectEvent"))
    case e: PopulationBreakdownEvent => Json.toJsObject(e)(PopulationBreakdownEvent.formatWithDiscriminator) + ("_type" -> JsString("PopulationBreakdownEvent"))
    case e: InstrumentObservationEvent => Json.toJsObject(e)(InstrumentObservationEvent.formatWithDiscriminator) + ("_type" -> JsString("InstrumentObservationEvent"))
    case e: MatchConsentEvent => Json.toJsObject(e)(MatchConsentEvent.formatWithDiscriminator) + ("_type" -> JsString("MatchConsentEvent"))
    case e: MatchListEvent => Json.toJsObject(e)(MatchListEvent.formatWithDiscriminator) + ("_type" -> JsString("MatchListEvent"))
    case e: MatchRequestEvent => Json.toJsObject(e)(MatchRequestEvent.formatWithDiscriminator) + ("_type" -> JsString("MatchRequestEvent"))
    case e: StrProfileEvent => Json.toJsObject(e)(StrProfileEvent.formatWithDiscriminator) + ("_type" -> JsString("StrProfileEvent"))
    case e: HaplogroupAncestralStrEvent => Json.toJsObject(e)(HaplogroupAncestralStrEvent.formatWithDiscriminator) + ("_type" -> JsString("HaplogroupAncestralStrEvent"))
    case e: WorkspaceEvent => Json.toJsObject(e)(WorkspaceEvent.formatWithDiscriminator) + ("_type" -> JsString("WorkspaceEvent"))
  }

  implicit val firehoseEventFormat: Format[FirehoseEvent] = Format(firehoseEventReads, firehoseEventWrites)
}
