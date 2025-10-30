package models.domain.genomics

import play.api.libs.json.{Json, OFormat}

import java.time.LocalDateTime

/**
 * Represents a sequencing laboratory that processes genomic samples.
 *
 * @param id                   Unique identifier for the lab
 * @param name                 Laboratory name (must be unique)
 * @param isD2c                Whether the lab offers direct-to-consumer services
 * @param websiteUrl           URL to the lab's official website
 * @param descriptionMarkdown  Rich text description (e.g., accreditation, methods)
 * @param createdAt            Timestamp when the record was created
 * @param updatedAt            Timestamp when the record was last updated
 */
case class SequencingLab(
                          id: Option[Int] = None,
                          name: String,
                          isD2c: Boolean = false,
                          websiteUrl: Option[String] = None,
                          descriptionMarkdown: Option[String] = None,
                          createdAt: LocalDateTime = LocalDateTime.now(),
                          updatedAt: Option[LocalDateTime] = None
                        )

object SequencingLab {
  implicit val format: OFormat[SequencingLab] = Json.format[SequencingLab]
}
