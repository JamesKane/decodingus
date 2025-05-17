package models

import java.time.LocalDateTime

case class HaplogroupVariantMetadata(
                                      haplogroup_variant_id: Int,
                                      revision_id: Int,
                                      author: String,
                                      timestamp: LocalDateTime,
                                      comment: String,
                                      change_type: String,
                                      previous_revision_id: Option[Int]
                                    )
