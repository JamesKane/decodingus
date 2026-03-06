package models.api

import play.api.libs.json.{Json, OFormat}

case class SequencerLabLookupResponse(
                                       instrumentId: String,
                                       labName: Option[String] = None,
                                       isD2c: Option[Boolean] = None,
                                       manufacturer: Option[String] = None,
                                       model: Option[String] = None,
                                       websiteUrl: Option[String] = None,
                                       source: String = "CURATOR",
                                       confidenceScore: Double = 1.0,
                                       observationCount: Int = 0,
                                       pendingProposal: Option[PendingProposalSummary] = None
                                     )

object SequencerLabLookupResponse {
  implicit val format: OFormat[SequencerLabLookupResponse] = Json.format[SequencerLabLookupResponse]
}

case class PendingProposalSummary(
                                   proposalId: Int,
                                   proposedLabName: String,
                                   observationCount: Int,
                                   confidenceScore: Double,
                                   status: String
                                 )

object PendingProposalSummary {
  implicit val format: OFormat[PendingProposalSummary] = Json.format[PendingProposalSummary]
}
