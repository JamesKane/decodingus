package models

import java.time.ZonedDateTime

case class PangenomeGraph(
                           id: Option[Long],
                           name: String,
                           description: Option[String],
                           creationDate: ZonedDateTime,
                           checksum: Option[String]
                         )