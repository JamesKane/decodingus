package models.domain.genomics

import play.api.libs.json.{Json, OFormat}

import java.time.LocalDateTime

case class InstrumentAssociationProposal(
                                          id: Option[Int] = None,
                                          instrumentId: String,
                                          proposedLabName: String,
                                          proposedManufacturer: Option[String] = None,
                                          proposedModel: Option[String] = None,
                                          existingLabId: Option[Int] = None,
                                          observationCount: Int = 0,
                                          distinctCitizenCount: Int = 0,
                                          confidenceScore: Double = 0.0,
                                          earliestObservation: Option[LocalDateTime] = None,
                                          latestObservation: Option[LocalDateTime] = None,
                                          status: ProposalStatus = ProposalStatus.Pending,
                                          reviewedAt: Option[LocalDateTime] = None,
                                          reviewedBy: Option[String] = None,
                                          reviewNotes: Option[String] = None,
                                          acceptedLabId: Option[Int] = None,
                                          acceptedInstrumentId: Option[Int] = None,
                                          createdAt: LocalDateTime = LocalDateTime.now(),
                                          updatedAt: LocalDateTime = LocalDateTime.now()
                                        )

object InstrumentAssociationProposal {
  implicit val format: OFormat[InstrumentAssociationProposal] = Json.format[InstrumentAssociationProposal]
}

sealed trait ProposalStatus {
  def dbValue: String
}

object ProposalStatus {
  case object Pending extends ProposalStatus { val dbValue = "PENDING" }
  case object ReadyForReview extends ProposalStatus { val dbValue = "READY_FOR_REVIEW" }
  case object UnderReview extends ProposalStatus { val dbValue = "UNDER_REVIEW" }
  case object Accepted extends ProposalStatus { val dbValue = "ACCEPTED" }
  case object Rejected extends ProposalStatus { val dbValue = "REJECTED" }
  case object Superseded extends ProposalStatus { val dbValue = "SUPERSEDED" }

  def fromString(s: String): ProposalStatus = s.toUpperCase match {
    case "PENDING" => Pending
    case "READY_FOR_REVIEW" => ReadyForReview
    case "UNDER_REVIEW" => UnderReview
    case "ACCEPTED" => Accepted
    case "REJECTED" => Rejected
    case "SUPERSEDED" => Superseded
    case other => throw new IllegalArgumentException(s"Unknown ProposalStatus: $other")
  }

  implicit val format: play.api.libs.json.Format[ProposalStatus] = new play.api.libs.json.Format[ProposalStatus] {
    def reads(json: play.api.libs.json.JsValue) = json match {
      case play.api.libs.json.JsString(s) => play.api.libs.json.JsSuccess(fromString(s))
      case _ => play.api.libs.json.JsError("String value expected")
    }
    def writes(s: ProposalStatus) = play.api.libs.json.JsString(s.dbValue)
  }
}

sealed trait AssociationSource {
  def dbValue: String
}

object AssociationSource {
  case object Curator extends AssociationSource { val dbValue = "CURATOR" }
  case object Consensus extends AssociationSource { val dbValue = "CONSENSUS" }
  case object Publication extends AssociationSource { val dbValue = "PUBLICATION" }

  def fromString(s: String): AssociationSource = s.toUpperCase match {
    case "CURATOR" => Curator
    case "CONSENSUS" => Consensus
    case "PUBLICATION" => Publication
    case other => throw new IllegalArgumentException(s"Unknown AssociationSource: $other")
  }

  implicit val format: play.api.libs.json.Format[AssociationSource] = new play.api.libs.json.Format[AssociationSource] {
    def reads(json: play.api.libs.json.JsValue) = json match {
      case play.api.libs.json.JsString(s) => play.api.libs.json.JsSuccess(fromString(s))
      case _ => play.api.libs.json.JsError("String value expected")
    }
    def writes(s: AssociationSource) = play.api.libs.json.JsString(s.dbValue)
  }
}
