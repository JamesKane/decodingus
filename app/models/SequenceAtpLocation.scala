package models

case class SequenceAtpLocation(
                                id: Option[Int],
                                sequenceFileId: Long,
                                repoDID: String,
                                recordCID: String,
                                recordPath: String,
                                indexDID: Option[String],
                                indexCID: Option[String],
                              )
