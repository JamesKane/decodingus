package models.api

import models.{EnaStudy, Publication}
import play.api.libs.json.{Json, OFormat}

case class PublicationWithEnaStudiesAndSampleCount(
                                                    publication: Publication,
                                                    enaStudies: Seq[EnaStudy],
                                                    sampleCount: Int,
                                                  )

object PublicationWithEnaStudiesAndSampleCount {
  implicit val publicationWithEnaStudiesAndSampleCountFormat: OFormat[PublicationWithEnaStudiesAndSampleCount] =
    Json.format[PublicationWithEnaStudiesAndSampleCount]
}
