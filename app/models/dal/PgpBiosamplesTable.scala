package models.dal

import models.PgpBiosample
import models.dal.MyPostgresProfile.api.*

import java.util.UUID

class PgpBiosamplesTable(tag: Tag) extends Table[PgpBiosample](tag, "pgp_biosample") {
  def id = column[Int]("pgp_biosample_id", O.PrimaryKey, O.AutoInc)
  def pgpParticipantId = column[String]("pgp_participant_id", O.Unique)
  def sex = column[String]("sex")
  def sampleGuid = column[UUID]("sample_guid")
  
  def * = (id.?, pgpParticipantId, sex, sampleGuid).mapTo[PgpBiosample]
}
