package models.domain

import models.HaplogroupType

import java.time.LocalDateTime

/**
 * Represents a haplogroup, which is a genetic population group of people who share a common ancestor
 * on the paternal or maternal line. This case class captures details about a haplogroup, including its 
 * type, name, lineage, and other metadata.
 *
 * @param id              An optional unique identifier for the haplogroup. Typically used for internal purposes.
 * @param name            The name of the haplogroup. This is required and serves as the primary identifier in a lineage context.
 * @param lineage         An optional description of the lineage to which the haplogroup belongs.
 * @param description     An optional textual description of the haplogroup providing additional context or details.
 * @param haplogroupType  The type of haplogroup (e.g., Y-DNA or mtDNA). This is represented as an enum.
 * @param revisionId      An integer that indicates the revision or version of the haplogroup data.
 * @param source          The source or origin of the haplogroup information for traceability purposes.
 * @param confidenceLevel A textual representation of the confidence level associated with assigning this haplogroup.
 * @param validFrom       The timestamp indicating when this haplogroup record became valid or effective.
 * @param validUntil      An optional timestamp indicating when this haplogroup record is no longer valid.
 */
case class Haplogroup(
                       id: Option[Int] = None,
                       name: String,
                       lineage: Option[String],
                       description: Option[String],
                       haplogroupType: HaplogroupType,
                       revisionId: Int,
                       source: String,
                       confidenceLevel: String,
                       validFrom: LocalDateTime,
                       validUntil: Option[LocalDateTime]
                     )