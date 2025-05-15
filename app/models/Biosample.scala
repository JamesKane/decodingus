package models

import com.vividsolutions.jts.geom.Point

import java.util.UUID

case class Biosample(
                      id: Option[Int] = None,
                      sampleAccession: String,
                      description: String,
                      alias: Option[String],
                      centerName: String,
                      sex: Option[String],
                      geocoord: Option[Point],
                      specimenDonorId: Option[Int],
                      sampleGuid: UUID
                    )