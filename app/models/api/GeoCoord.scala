package models.api

import play.api.libs.json.{Json, OFormat}

case class GeoCoord(lat: Double, lon: Double)

object GeoCoord {
  implicit val geoCoordFormat: OFormat[GeoCoord] = Json.format[GeoCoord]
}