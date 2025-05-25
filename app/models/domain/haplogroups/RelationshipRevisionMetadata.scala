package models.domain.haplogroups

import java.time.LocalDateTime

/**
 * Represents metadata related to a revision of a haplogroup relationship. This metadata captures
 * important details about the changes made, the author of the revision, and other associated information.
 *
 * @param haplogroup_relationship_id The unique identifier of the haplogroup relationship being revised.
 * @param revisionId                 A unique integer identifier representing this specific revision.
 * @param author                     The name or identifier of the individual or entity that authored the revision.
 * @param timestamp                  The timestamp indicating when the revision was made.
 * @param comment                    A textual comment provided by the author to describe or explain the revision.
 * @param changeType                 A string describing the type of change made (e.g., "update", "delete", "create").
 * @param previousRevisionId         An optional integer identifier referencing the immediately preceding revision, if any.
 */
case class RelationshipRevisionMetadata(
                                         haplogroup_relationship_id: Int,
                                         revisionId: Int,
                                         author: String,
                                         timestamp: LocalDateTime,
                                         comment: String,
                                         changeType: String,
                                         previousRevisionId: Option[Int]
                                       )