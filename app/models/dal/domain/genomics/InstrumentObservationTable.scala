package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.{InstrumentObservation, ObservationConfidence}
import slick.ast.BaseTypedType

import java.time.LocalDateTime

class InstrumentObservationTable(tag: Tag) extends Table[InstrumentObservation](tag, "instrument_observation") {

  implicit private val confidenceMapper: BaseTypedType[ObservationConfidence] =
    MappedColumnType.base[ObservationConfidence, String](_.dbValue, ObservationConfidence.fromString)

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def atUri = column[String]("at_uri", O.Unique)
  def atCid = column[Option[String]]("at_cid")
  def instrumentId = column[String]("instrument_id")
  def labName = column[String]("lab_name")
  def biosampleRef = column[String]("biosample_ref")
  def sequenceRunRef = column[Option[String]]("sequence_run_ref")
  def platform = column[Option[String]]("platform")
  def instrumentModel = column[Option[String]]("instrument_model")
  def flowcellId = column[Option[String]]("flowcell_id")
  def runDate = column[Option[LocalDateTime]]("run_date")
  def confidence = column[ObservationConfidence]("confidence")
  def createdAt = column[LocalDateTime]("created_at")
  def updatedAt = column[Option[LocalDateTime]]("updated_at")

  def * = (
    id.?, atUri, atCid, instrumentId, labName, biosampleRef,
    sequenceRunRef, platform, instrumentModel, flowcellId,
    runDate, confidence, createdAt, updatedAt
  ).mapTo[InstrumentObservation]
}
