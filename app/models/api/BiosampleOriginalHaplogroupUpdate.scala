package models.api

import play.api.libs.json.{Format, Json}

case class BiosampleOriginalHaplogroupUpdate(
                                              originalYHaplogroup: Option[String],
                                              originalMtHaplogroup: Option[String],
                                              notes: Option[String]
                                            )

object BiosampleOriginalHaplogroupUpdate {
  implicit val format: Format[BiosampleOriginalHaplogroupUpdate] = Json.format
}

case class BiosampleOriginalHaplogroupView(
                                            id: Option[Int],
                                            biosampleId: Int,
                                            publicationId: Int,
                                            originalYHaplogroup: Option[String],
                                            originalMtHaplogroup: Option[String],
                                            notes: Option[String]
                                          )

object BiosampleOriginalHaplogroupView {
  implicit val format: Format[BiosampleOriginalHaplogroupView] = Json.format

  def fromDomain(domain: models.domain.publications.BiosampleOriginalHaplogroup): BiosampleOriginalHaplogroupView =
    BiosampleOriginalHaplogroupView(
      id = domain.id,
      biosampleId = domain.biosampleId,
      publicationId = domain.publicationId,
      originalYHaplogroup = domain.originalYHaplogroup,
      originalMtHaplogroup = domain.originalMtHaplogroup,
      notes = domain.notes
    )
}