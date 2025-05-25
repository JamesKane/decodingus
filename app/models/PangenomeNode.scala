package models

case class PangenomeNode(
                          id: Option[Long],
                          graphId: Int,
                          sequence: String,
                          length: Int,
                          isCore: Option[Boolean],
                          annotationId: Option[Int]
                        )