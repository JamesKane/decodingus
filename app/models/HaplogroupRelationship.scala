package models

import java.time.LocalDateTime

case class HaplogroupRelationship(
                                   id: Option[Int] = None,
                                   childHaplogroupId: Int,
                                   parentHaplogroupId: Int,
                                   revisionId: Int,
                                   validFrom: LocalDateTime,
                                   validUntil: Option[LocalDateTime],
                                   source: String
                                 )