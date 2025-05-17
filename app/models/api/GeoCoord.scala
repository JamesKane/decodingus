package models.api

import play.api.libs.json.{Json, OFormat}

/**
 * Represents a geographical coordinate with latitude and longitude values.
 *
 * @param lat Latitude value of the geographical coordinate. Positive values represent the northern hemisphere, and negative values represent the southern hemisphere.
 * @param lon Longitude value of the geographical coordinate. Positive values represent the eastern hemisphere, and negative values represent the western hemisphere.
 */
case class GeoCoord(lat: Double, lon: Double)

/**
 * Companion object for the `GeoCoord` case class.
 *
 * Provides an implicit JSON formatter for serializing and deserializing
 * `GeoCoord` instances using the Play Framework's JSON library.
 *
 * This formatter can be used to automatically convert `GeoCoord` objects
 * to and from their JSON representation, enabling seamless integration
 * with APIs or storage systems that utilize JSON.
 */
object GeoCoord {
  implicit val geoCoordFormat: OFormat[GeoCoord] = Json.format[GeoCoord]
}