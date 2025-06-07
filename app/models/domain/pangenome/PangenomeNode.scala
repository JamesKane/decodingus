package models.domain.pangenome

case class PangenomeNode(
                          id: Option[Long],
                          graphId: Long,
                          nodeName: String,
                          sequenceLength: Option[Long]
                        )
