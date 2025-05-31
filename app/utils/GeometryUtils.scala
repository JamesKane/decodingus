package utils

import com.vividsolutions.jts.geom.{Coordinate, GeometryFactory, Point}
import models.api.GeoCoord

/**
 * Utility object providing geometric transformation functions.
 */
object GeometryUtils {
  private val geometryFactory = new GeometryFactory()

  /**
   * Converts a geographical coordinate to a geometric point.
   *
   * @param geoCoord the geographical coordinate containing latitude and longitude values
   * @return a Point object representing the given geographical coordinate
   */
  def geoCoordToPoint(geoCoord: GeoCoord): Point = {
    geometryFactory.createPoint(new Coordinate(geoCoord.lon, geoCoord.lat))
  }

  def createPoint(lat: Double, lon: Double): Point = {
    geometryFactory.createPoint(new Coordinate(lon, lat))
  }
}