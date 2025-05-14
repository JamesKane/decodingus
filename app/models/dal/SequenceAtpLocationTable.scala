package models.dal

import models.SequenceAtpLocation
import slick.jdbc.PostgresProfile.api.*

class SequenceAtpLocationTable(tag: Tag) extends Table[SequenceAtpLocation](tag, "sequence_atp_location") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def sequenceFileId = column[Int]("sequence_file_id")
  def repoDid = column[String]("repo_did")
  def recordCid = column[String]("record_cid")
  def recordPath = column[String]("record_path")
  def indexDid = column[String]("index_did")
  def indexCid = column[String]("index_cid")
  
  def * = (id.?, sequenceFileId, repoDid, recordCid, recordPath, indexDid, indexCid).mapTo[SequenceAtpLocation]
}
