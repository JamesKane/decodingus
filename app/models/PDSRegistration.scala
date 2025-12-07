package models

import play.api.libs.json.{Format, Json}

import java.time.ZonedDateTime // Import Play-JSON classes

case class PDSRegistration(
                            did: String,
                            pdsUrl: String,
                            handle: String,
                            lastCommitCid: Option[String],
                            lastCommitSeq: Option[Long],
                            cursor: Long = 0L,
                            createdAt: ZonedDateTime,
                            updatedAt: ZonedDateTime,
                            leasedByInstanceId: Option[String] = None,
                            leaseExpiresAt: Option[ZonedDateTime] = None,
                            processingStatus: String = "idle"
                          )

object PDSRegistration {
  implicit val format: Format[PDSRegistration] = Json.format[PDSRegistration]
}
