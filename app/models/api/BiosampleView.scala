package models.api

import models.domain.genomics.{Biosample, SpecimenDonor}
import play.api.libs.json.{Json, OFormat}
import utils.GeometryUtils

import java.util.UUID

/**
 * Represents a view of a biosample with relevant metadata and attributes.
 *
 * @param id              Optional identifier for the biosample, typically used for internal purposes.
 * @param sampleAccession Unique accession identifier for the biosample, often used in external systems.
 * @param description     Description or details about the biosample.
 * @param alias           Optional alternative name or alias associated with the biosample.
 * @param centerName      Name of the center or organization responsible for the biosample.
 * @param sex             Optional biological sex associated with the biosample, if applicable.
 * @param geoCoord        Optional geographical location as a set of latitude and longitude coordinates.
 * @param specimenDonorId Optional identifier for the donor of the specimen associated with the biosample.
 * @param sampleGuid      Globally unique identifier (GUID) for the biosample.
 * @param locked          Boolean flag indicating whether the biosample is locked for further modifications.
 * @param dateRangeStart  Optional start of the date range associated with the biosample.
 * @param dateRangeEnd    Optional end of the date range associated with the biosample.
 */
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
                          locked: Boolean,
                          dateRangeStart: Option[Int],
                          dateRangeEnd: Option[Int]
                        )

/**
 * Companion object for the BiosampleView case class.
 *
 * Provides functionality for serializing and deserializing BiosampleView instances,
 * as well as a method to convert a domain object of type Biosample into a BiosampleView.
 */
object BiosampleView {
  implicit val format: OFormat[BiosampleView] = Json.format[BiosampleView]

  def fromDomain(biosample: Biosample, specimenDonor: Option[SpecimenDonor] = None): BiosampleView = {
    BiosampleView(
      id = biosample.id,
      sampleAccession = biosample.sampleAccession,
      description = biosample.description,
      alias = biosample.alias,
      centerName = biosample.centerName,
      sex = specimenDonor.flatMap(_.sex.map(_.toString)),
      geoCoord = specimenDonor.flatMap(_.geocoord).map(point => GeoCoord(point.getY, point.getX)),
      specimenDonorId = biosample.specimenDonorId,
      sampleGuid = biosample.sampleGuid,
      locked = biosample.locked,
      dateRangeStart = specimenDonor.flatMap(_.dateRangeStart),
      dateRangeEnd = specimenDonor.flatMap(_.dateRangeEnd)
    )
  }
}

