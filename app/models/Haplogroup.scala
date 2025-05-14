package models

import java.time.LocalDateTime

case class Haplogroup(
                       haplogroupId: Option[Int] = None,
                       name: String,
                       lineage: Option[String],
                       description: Option[String],
                       haplogroupType: String,
                       revisionId: Int,
                       source: String,
                       confidenceLevel: String,
                       validFrom: LocalDateTime,
                       validUntil: Option[LocalDateTime]
                     )