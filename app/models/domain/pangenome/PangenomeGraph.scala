package models.domain.pangenome

import java.time.ZonedDateTime

case class PangenomeGraph(
                           id: Option[Long],
                           graphName: String,
                           sourceGfaFile: Option[String],
                           description: Option[String],
                           creationDate: ZonedDateTime
                         )