package models.api

import play.api.libs.json.{Json, Reads}

case class BiosampleUpdate(
                            sex: Option[String] = None,
                            geoCoord: Option[GeoCoord] = None,
                            alias: Option[String] = None,
                            locked: Option[Boolean] = None,
                            dateRangeStart: Option[Int] = None,
                            dateRangeEnd: Option[Int] = None
                          )

object BiosampleUpdate {
  implicit val reads: Reads[BiosampleUpdate] = Json.reads[BiosampleUpdate]
}