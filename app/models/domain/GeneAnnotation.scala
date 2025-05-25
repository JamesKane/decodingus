package models.domain

case class GeneAnnotation(
                           id: Option[Long],
                           geneSymbol: Option[String],
                           geneId: Option[String],
                           description: Option[String],
                           representativeSequenceNodeId: Option[Int]
                         )