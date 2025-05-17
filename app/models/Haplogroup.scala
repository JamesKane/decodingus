package models

import java.time.LocalDateTime

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