package models.domain.pds

import play.api.libs.json.{JsValue, Json, OFormat}

import java.time.LocalDateTime
import java.util.UUID

case class PdsSubmission(
                          id: Option[Int] = None,
                          pdsNodeId: Int,
                          submissionType: String,
                          biosampleId: Option[Int] = None,
                          biosampleGuid: Option[UUID] = None,
                          proposedValue: String,
                          confidenceScore: Option[Double] = None,
                          algorithmVersion: Option[String] = None,
                          softwareVersion: Option[String] = None,
                          payload: Option[JsValue] = None,
                          status: String = "PENDING",
                          reviewedBy: Option[String] = None,
                          reviewedAt: Option[LocalDateTime] = None,
                          reviewNotes: Option[String] = None,
                          atUri: Option[String] = None,
                          atCid: Option[String] = None,
                          createdAt: LocalDateTime = LocalDateTime.now()
                        )

object PdsSubmission {
  implicit val format: OFormat[PdsSubmission] = Json.format[PdsSubmission]

  val ValidTypes: Set[String] = Set("HAPLOGROUP_CALL", "VARIANT_CALL", "BRANCH_PROPOSAL", "PRIVATE_VARIANT", "STR_PROFILE")
  val ValidStatuses: Set[String] = Set("PENDING", "ACCEPTED", "REJECTED", "SUPERSEDED")
}

case class SubmissionSummary(
                              pdsNodeId: Int,
                              did: String,
                              totalSubmissions: Int,
                              pendingCount: Int,
                              acceptedCount: Int,
                              rejectedCount: Int,
                              acceptanceRate: Double
                            )

object SubmissionSummary {
  implicit val format: OFormat[SubmissionSummary] = Json.format[SubmissionSummary]
}
