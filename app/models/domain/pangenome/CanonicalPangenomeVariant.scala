package models.domain.pangenome

import java.time.ZonedDateTime

case class CanonicalPangenomeVariant(
                                      id: Option[Long],
                                      pangenomeGraphId: Long,
                                      variantType: String,
                                      variantNodes: List[Int],
                                      variantEdges: List[Int],
                                      referencePathId: Option[Long],
                                      referenceStartPosition: Option[Int],
                                      referenceEndPosition: Option[Int],
                                      referenceAlleleSequence: Option[String],
                                      alternateAlleleSequence: Option[String],
                                      canonicalHash: String,
                                      description: Option[String],
                                      creationDate: ZonedDateTime
                                    )
