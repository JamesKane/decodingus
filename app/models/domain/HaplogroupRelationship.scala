package models.domain

import java.time.LocalDateTime

/**
 * Represents a relationship between a child haplogroup and its parent haplogroup.
 * This case class maintains the hierarchy and lineage history of haplogroups over time.
 *
 * @param id                 An optional unique identifier for this relationship record.
 * @param childHaplogroupId  The unique identifier of the child haplogroup in the relationship.
 * @param parentHaplogroupId The unique identifier of the parent haplogroup in the relationship.
 * @param revisionId         An integer indicating the revision associated with this relationship record.
 * @param validFrom          The starting timestamp from which this relationship is considered valid.
 * @param validUntil         An optional timestamp indicating the expiration or invalidation of this relationship.
 * @param source             The source or origin of the information defining this relationship, for traceability.
 */
case class HaplogroupRelationship(
                                   id: Option[Int] = None,
                                   childHaplogroupId: Int,
                                   parentHaplogroupId: Int,
                                   revisionId: Int,
                                   validFrom: LocalDateTime,
                                   validUntil: Option[LocalDateTime],
                                   source: String
                                 )