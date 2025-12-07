package models.domain.publications

import models.domain.genomics.HaplogroupResult

case class CitizenBiosampleOriginalHaplogroup(
                                               id: Option[Int] = None,
                                               citizenBiosampleId: Int,
                                               publicationId: Int,
                                               originalYHaplogroup: Option[HaplogroupResult],
                                               originalMtHaplogroup: Option[HaplogroupResult],
                                               notes: Option[String]
                                             )
