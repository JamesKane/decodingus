package models.api

import models.domain.genomics.HaplogroupResult
import play.api.libs.json.{Json, OFormat}

case class HaplogroupAssignments(
                                  yDna: Option[HaplogroupResult],
                                  mtDna: Option[HaplogroupResult]
                                )

object HaplogroupAssignments {
  implicit val format: OFormat[HaplogroupAssignments] = Json.format[HaplogroupAssignments]
}
