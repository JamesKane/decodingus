package utils

import com.vividsolutions.jts.geom.{Coordinate, GeometryFactory, Point}
import models.api.GeoCoord

object GeometryUtils {
  private val geometryFactory = new GeometryFactory()

  def geoCoordToPoint(geoCoord: GeoCoord): Point = {
    geometryFactory.createPoint(new Coordinate(geoCoord.lon, geoCoord.lat))
  }
}