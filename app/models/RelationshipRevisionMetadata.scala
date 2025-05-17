package models

import java.time.LocalDateTime

case class RelationshipRevisionMetadata(
                                         haplogroup_relationship_id: Int,
                                         revisionId: Int,
                                         author: String,
                                         timestamp: LocalDateTime,
                                         comment: String,
                                         changeType: String,
                                         previousRevisionId: Option[Int]
                                       )