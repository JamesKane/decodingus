package models

case class SequenceHttpLocation(
                                 id: Option[Int],
                                 sequenceFileId: Int,
                                 fileUrl: String,
                                 fileIndexUrl: Option[String],
                               )
