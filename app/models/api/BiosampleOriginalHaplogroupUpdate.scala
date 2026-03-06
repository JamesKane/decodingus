package models.api

import models.domain.genomics.{HaplogroupResult, OriginalHaplogroupEntry}
import play.api.libs.json.{Format, Json}

case class BiosampleOriginalHaplogroupUpdate(
                                              originalYHaplogroup: Option[HaplogroupResult],
                                              originalMtHaplogroup: Option[HaplogroupResult],
                                              notes: Option[String]
                                            )

object BiosampleOriginalHaplogroupUpdate {
  implicit val format: Format[BiosampleOriginalHaplogroupUpdate] = Json.format
}

case class BiosampleOriginalHaplogroupView(
                                            biosampleId: Int,
                                            publicationId: Int,
                                            originalYHaplogroup: Option[HaplogroupResult],
                                            originalMtHaplogroup: Option[HaplogroupResult],
                                            notes: Option[String]
                                          )

object BiosampleOriginalHaplogroupView {
  implicit val format: Format[BiosampleOriginalHaplogroupView] = Json.format

  def fromEntry(biosampleId: Int, entry: OriginalHaplogroupEntry): BiosampleOriginalHaplogroupView =
    BiosampleOriginalHaplogroupView(
      biosampleId = biosampleId,
      publicationId = entry.publicationId,
      originalYHaplogroup = entry.yHaplogroupResult,
      originalMtHaplogroup = entry.mtHaplogroupResult,
      notes = entry.notes
    )
}
