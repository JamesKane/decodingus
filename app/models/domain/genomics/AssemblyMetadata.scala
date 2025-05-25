package models.domain.genomics

import play.api.libs.json.JsValue

import java.time.LocalDate

case class AssemblyMetadata(
                             id: Option[Long],
                             assemblyName: String,
                             accession: Option[String],
                             releaseDate: Option[LocalDate],
                             sourceOrganism: Option[String],
                             assemblyLevel: Option[String],
                             metadata: Option[JsValue]
                           )