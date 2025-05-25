package models

import java.time.LocalDate
import play.api.libs.json.JsValue

case class AssemblyMetadata(
                             id: Option[Long],
                             assemblyName: String,
                             accession: Option[String],
                             releaseDate: Option[LocalDate],
                             sourceOrganism: Option[String],
                             assemblyLevel: Option[String],
                             metadata: Option[JsValue]
                           )