package models.dal

import models.SequenceHttpLocation
import slick.jdbc.PostgresProfile.api.*

class SequenceHttpLocationTable(tag: Tag) extends Table[SequenceHttpLocation](tag, "sequence_http_location") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def sequenceFileId = column[Int]("sequence_file_id")

  def fileUrl = column[String]("file_url")

  def fileIndexUrl = column[Option[String]]("file_index_url")

  def * = (id.?, sequenceFileId, fileUrl, fileIndexUrl).mapTo[SequenceHttpLocation]
}
