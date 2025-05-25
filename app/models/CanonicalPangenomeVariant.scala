package models

import java.time.ZonedDateTime

case class CanonicalPangenomeVariant(
                                      id: Option[Long],
                                      panGenomeGraphId: Int,
                                      variantType: String,
                                      variantNodes: List[Int],
                                      variantEdges: List[Int],
                                      referencePathId: Option[Int],
                                      referenceStartPosition: Option[Int],
                                      referenceEndPosition: Option[Int],
                                      referenceAlleleSequence: Option[String],
                                      alternateAlleleSequence: Option[String],
                                      canonicalHash: String,
                                      description: Option[String],
                                      creationDate: ZonedDateTime
                                    )
