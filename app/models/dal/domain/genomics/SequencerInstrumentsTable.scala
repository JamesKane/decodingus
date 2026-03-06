package models.dal.domain.genomics

import models.dal.MyPostgresProfile
import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.SequencerInstrument
import slick.lifted.{ProvenShape, Tag}

import java.time.LocalDateTime

class SequencerInstrumentsTable(tag: Tag) extends MyPostgresProfile.api.Table[SequencerInstrument](tag, "sequencer_instrument") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def instrumentId = column[String]("instrument_id")

  def labId = column[Int]("lab_id")

  def manufacturer = column[Option[String]]("manufacturer")

  def model = column[Option[String]]("model")

  def source = column[Option[String]]("source")

  def observationCount = column[Int]("observation_count")

  def confidenceScore = column[Double]("confidence_score")

  def lastObservedAt = column[Option[LocalDateTime]]("last_observed_at")

  def createdAt = column[LocalDateTime]("created_at")

  def updatedAt = column[Option[LocalDateTime]]("updated_at")

  override def * : ProvenShape[SequencerInstrument] = (
    id.?,
    instrumentId,
    labId,
    manufacturer,
    model,
    source,
    observationCount,
    confidenceScore,
    lastObservedAt,
    createdAt,
    updatedAt
  ).mapTo[SequencerInstrument]

  // Unique index on instrument_id
  def instrumentIdIdx = index("sequencer_instrument_instrument_id_uindex", instrumentId, unique = true)
}