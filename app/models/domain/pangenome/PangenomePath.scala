package models.domain.pangenome

case class PangenomePath(
                          id: Option[Long],
                          graphId: Long,
                          pathName: String,
                          isReference: Boolean,
                          lengthBp: Option[Long],
                          description: Option[String]
                        )
