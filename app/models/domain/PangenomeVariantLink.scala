package models.domain

import java.time.ZonedDateTime

case class PangenomeVariantLink(
                                 id: Option[Long],
                                 variantId: Int,
                                 canonicalPangenomeVariantId: Int,
                                 pangenomeGraphId: Int,
                                 description: Option[String],
                                 mappingSource: String,
                                 mappingDate: ZonedDateTime
                               )