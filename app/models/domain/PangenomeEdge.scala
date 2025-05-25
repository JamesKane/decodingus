package models.domain

case class PangenomeEdge(
                          id: Option[Long],
                          graphId: Int,
                          sourceNodeId: Int,
                          targetNodeId: Int,
                          sourceOrientation: String,
                          targetOrientation: String,
                          edgeType: Option[String]
                        )