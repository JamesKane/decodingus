package models.domain.pangenome

case class PangenomePath(
                          id: Option[Long],
                          graphId: Int,
                          name: String,
                          nodeSequence: List[Int],
                          length: Long,
                          sourceAssemblyId: Option[Int]
                        )