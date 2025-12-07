package models.api.genomics

import play.api.libs.json.{Json, OFormat}

case class SequencingLabCreateRequest(
                                       name: String,
                                       isD2c: Option[Boolean] = None,
                                       websiteUrl: Option[String] = None,
                                       descriptionMarkdown: Option[String] = None
                                     )

object SequencingLabCreateRequest {
  implicit val format: OFormat[SequencingLabCreateRequest] = Json.format[SequencingLabCreateRequest]
}

case class SequencingLabUpdateRequest(
                                       name: Option[String] = None,
                                       isD2c: Option[Boolean] = None,
                                       websiteUrl: Option[String] = None,
                                       descriptionMarkdown: Option[String] = None
                                     )

object SequencingLabUpdateRequest {
  implicit val format: OFormat[SequencingLabUpdateRequest] = Json.format[SequencingLabUpdateRequest]
}
