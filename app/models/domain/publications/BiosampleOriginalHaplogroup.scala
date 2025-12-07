package models.domain.publications

import models.domain.genomics.HaplogroupResult

/**
 * Represents an original haplogroup assignment for a biosample from a specific publication.
 *
 * @param id                   The unique identifier for this haplogroup assignment
 * @param biosampleId          The ID of the associated biosample
 * @param publicationId        The ID of the publication where this haplogroup was reported
 * @param originalYHaplogroup  The original Y chromosome haplogroup assignment
 * @param originalMtHaplogroup The original mitochondrial DNA haplogroup assignment
 * @param notes                Additional notes or comments about the haplogroup assignment
 */
case class BiosampleOriginalHaplogroup(
                                        id: Option[Int] = None,
                                        biosampleId: Int,
                                        publicationId: Int,
                                        originalYHaplogroup: Option[HaplogroupResult],
                                        originalMtHaplogroup: Option[HaplogroupResult],
                                        notes: Option[String]
                                      )

