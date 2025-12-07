package models.domain.genomics

import play.api.libs.json.{Json, OFormat}

case class HaplogroupResult(
                             haplogroupName: String,
                             score: Double,
                             matchingSnps: Int,
                             mismatchingSnps: Int,
                             ancestralMatches: Int,
                             treeDepth: Int,
                             lineagePath: Seq[String]
                           )

object HaplogroupResult {
  implicit val format: OFormat[HaplogroupResult] = Json.format[HaplogroupResult]
}
