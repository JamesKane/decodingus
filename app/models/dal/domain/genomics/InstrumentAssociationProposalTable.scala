package models.dal.domain.genomics

import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.{InstrumentAssociationProposal, ProposalStatus}
import slick.lifted.{ProvenShape, Tag}

import java.time.LocalDateTime

class InstrumentAssociationProposalTable(tag: Tag)
  extends MyPostgresProfile.api.Table[InstrumentAssociationProposal](tag, "instrument_association_proposal") {

  implicit val proposalStatusMapper: BaseColumnType[ProposalStatus] =
    MappedColumnType.base[ProposalStatus, String](_.dbValue, ProposalStatus.fromString)

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def instrumentId = column[String]("instrument_id")
  def proposedLabName = column[String]("proposed_lab_name")
  def proposedManufacturer = column[Option[String]]("proposed_manufacturer")
  def proposedModel = column[Option[String]]("proposed_model")
  def existingLabId = column[Option[Int]]("existing_lab_id")
  def observationCount = column[Int]("observation_count")
  def distinctCitizenCount = column[Int]("distinct_citizen_count")
  def confidenceScore = column[Double]("confidence_score")
  def earliestObservation = column[Option[LocalDateTime]]("earliest_observation")
  def latestObservation = column[Option[LocalDateTime]]("latest_observation")
  def status = column[ProposalStatus]("status")
  def reviewedAt = column[Option[LocalDateTime]]("reviewed_at")
  def reviewedBy = column[Option[String]]("reviewed_by")
  def reviewNotes = column[Option[String]]("review_notes")
  def acceptedLabId = column[Option[Int]]("accepted_lab_id")
  def acceptedInstrumentId = column[Option[Int]]("accepted_instrument_id")
  def createdAt = column[LocalDateTime]("created_at")
  def updatedAt = column[LocalDateTime]("updated_at")

  override def * : ProvenShape[InstrumentAssociationProposal] = (
    id.?,
    instrumentId,
    proposedLabName,
    proposedManufacturer,
    proposedModel,
    existingLabId,
    observationCount,
    distinctCitizenCount,
    confidenceScore,
    earliestObservation,
    latestObservation,
    status,
    reviewedAt,
    reviewedBy,
    reviewNotes,
    acceptedLabId,
    acceptedInstrumentId,
    createdAt,
    updatedAt
  ).mapTo[InstrumentAssociationProposal]
}
