package models.domain.genomics

import play.api.libs.json.{Json, OFormat}

import java.time.LocalDateTime

case class InstrumentObservation(
                                  id: Option[Int] = None,
                                  atUri: String,
                                  atCid: Option[String] = None,
                                  instrumentId: String,
                                  labName: String,
                                  biosampleRef: String,
                                  sequenceRunRef: Option[String] = None,
                                  platform: Option[String] = None,
                                  instrumentModel: Option[String] = None,
                                  flowcellId: Option[String] = None,
                                  runDate: Option[LocalDateTime] = None,
                                  confidence: ObservationConfidence = ObservationConfidence.Inferred,
                                  createdAt: LocalDateTime = LocalDateTime.now(),
                                  updatedAt: Option[LocalDateTime] = None
                                )

object InstrumentObservation {
  implicit val format: OFormat[InstrumentObservation] = Json.format[InstrumentObservation]
}

sealed trait ObservationConfidence {
  def dbValue: String
}

object ObservationConfidence {
  case object Known extends ObservationConfidence { val dbValue = "KNOWN" }
  case object Inferred extends ObservationConfidence { val dbValue = "INFERRED" }
  case object Guessed extends ObservationConfidence { val dbValue = "GUESSED" }

  def fromString(s: String): ObservationConfidence = s.toUpperCase match {
    case "KNOWN" => Known
    case "INFERRED" => Inferred
    case "GUESSED" => Guessed
    case other => throw new IllegalArgumentException(s"Unknown ObservationConfidence: $other")
  }

  implicit val format: play.api.libs.json.Format[ObservationConfidence] = new play.api.libs.json.Format[ObservationConfidence] {
    def reads(json: play.api.libs.json.JsValue) = json match {
      case play.api.libs.json.JsString(s) => play.api.libs.json.JsSuccess(fromString(s))
      case _ => play.api.libs.json.JsError("String value expected")
    }
    def writes(c: ObservationConfidence) = play.api.libs.json.JsString(c.dbValue)
  }
}
