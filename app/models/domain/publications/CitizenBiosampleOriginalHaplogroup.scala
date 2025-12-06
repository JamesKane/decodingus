package models.domain.publications

case class CitizenBiosampleOriginalHaplogroup(
  id: Option[Int] = None,
  citizenBiosampleId: Int,
  publicationId: Int,
  originalYHaplogroup: Option[String],
  originalMtHaplogroup: Option[String],
  notes: Option[String]
)
