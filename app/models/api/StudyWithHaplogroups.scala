package models.api

import play.api.libs.json.{Json, OFormat}

case class StudyWithHaplogroups(
                                 accession: String,
                                 title: String,
                                 centerName: String,
                                 source: String,
                                 yHaplogroup: Option[String],
                                 mtHaplogroup: Option[String],
                                 notes: Option[String]
                               )

object StudyWithHaplogroups {
  implicit val format: OFormat[StudyWithHaplogroups] = Json.format
}

case class SampleWithStudies(
                              sampleName: Option[String],
                              accession: String,
                              sex: Option[String],
                              geoCoord: Option[GeoCoord],
                              studies: List[StudyWithHaplogroups]
                            )

object SampleWithStudies {
  implicit val format: OFormat[SampleWithStudies] = Json.format
}
