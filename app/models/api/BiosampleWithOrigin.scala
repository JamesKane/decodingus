package models.api

import play.api.libs.json.{Json, OFormat}

case class BiosampleWithOrigin(sampleName: Option[String], enaAccession: String, sex: Option[String], yDnaHaplogroup: Option[String], mtDnaHaplogroup: Option[String], geoCoord: Option[GeoCoord]) {
  def formattedOrigin: String = geoCoord match {
    case Some(lat, lon) =>
      val latDir = if (lat >= 0) "N" else "S"
      val lonDir = if (lon >= 0) "E" else "W"
      f"${math.abs(lat)}%.2f°$latDir, ${math.abs(lon)}%.2f°$lonDir"
    case None =>
      "Origin Not Available"
  }
}

object BiosampleWithOrigin {
  implicit val biosampleWithOriginFormat: OFormat[BiosampleWithOrigin] = Json.format[BiosampleWithOrigin]
}

