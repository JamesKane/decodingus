package models.domain

import java.time.LocalDateTime

/**
 * Represents metadata for changes or revisions associated with haplogroup variants.
 *
 * @param haplogroup_variant_id The unique identifier for the haplogroup variant associated with this metadata entry.
 * @param revision_id           An integer identifier for the specific revision of the haplogroup variant.
 * @param author                The name or identifier of the person or entity that authored this revision.
 * @param timestamp             The timestamp indicating when this revision was made.
 * @param comment               A textual comment or description associated with the revision, providing context or notes.
 * @param change_type           A description of the type of change represented by this revision (e.g., 'update', 'create', 'delete').
 * @param previous_revision_id  An optional identifier for the previous revision in the sequence, if applicable.
 */
case class HaplogroupVariantMetadata(
                                      haplogroup_variant_id: Int,
                                      revision_id: Int,
                                      author: String,
                                      timestamp: LocalDateTime,
                                      comment: String,
                                      change_type: String,
                                      previous_revision_id: Option[Int]
                                    )
