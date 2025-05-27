package models.api

import models.domain.genomics.Biosample
import play.api.libs.json.{Json, OFormat}
import utils.GeometryUtils

import java.util.UUID

case class BiosampleView(
                          id: Option[Int],
                          sampleAccession: String,
                          description: String,
                          alias: Option[String],
                          centerName: String,
                          sex: Option[String],
                          geoCoord: Option[GeoCoord],
                          specimenDonorId: Option[Int],
                          sampleGuid: UUID,
                          locked: Boolean
                        )

object BiosampleView {
  implicit val format: OFormat[BiosampleView] = Json.format[BiosampleView]

  def fromDomain(biosample: Biosample): BiosampleView = {
    BiosampleView(
      id = biosample.id,
      sampleAccession = biosample.sampleAccession,
      description = biosample.description,
      alias = biosample.alias,
      centerName = biosample.centerName,
      sex = biosample.sex,
      geoCoord = biosample.geocoord.map(point => GeoCoord(point.getY, point.getX)),
      specimenDonorId = biosample.specimenDonorId,
      sampleGuid = biosample.sampleGuid,
      locked = biosample.locked
    )
  }

  def toDomain(view: BiosampleView): Biosample = {
    Biosample(
      id = view.id,
      sampleAccession = view.sampleAccession,
      description = view.description,
      alias = view.alias,
      centerName = view.centerName,
      sex = view.sex,
      geocoord = view.geoCoord.map(GeometryUtils.geoCoordToPoint),
      specimenDonorId = view.specimenDonorId,
      sampleGuid = view.sampleGuid,
      locked = view.locked
    )
  }
}