package models

import java.time.ZonedDateTime
import play.api.libs.json.{Format, Json} // Import Play-JSON classes

case class PDSRegistration(
  did: String,
  pdsUrl: String,
  handle: String,
  lastCommitCid: Option[String],
  lastCommitSeq: Option[Long],
  cursor: Long = 0L,
  createdAt: ZonedDateTime,
  updatedAt: ZonedDateTime
)

object PDSRegistration {
  implicit val format: Format[PDSRegistration] = Json.format[PDSRegistration]
}
