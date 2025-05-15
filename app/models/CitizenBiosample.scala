package models

import com.vividsolutions.jts.geom.Point

import java.time.LocalDate
import java.util.UUID

case class CitizenBiosample(
                             id: Option[Int], 
                             citizenBiosampleDid: String,
                             sourcePlatform: Option[String],
                             collectionDate: Option[LocalDate],
                             coord: Option[Point],
                             description: Option[String],
                             sampleGuid: UUID
                           )
