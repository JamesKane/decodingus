package services

import com.vividsolutions.jts.geom.Point
import utils.GeometryUtils

import scala.concurrent.Future

trait CoordinateValidation {
  def validateCoordinates(lat: Option[Double], lon: Option[Double]): Future[Option[Point]] = {
    (lat, lon) match {
      case (Some(latitude), Some(longitude)) =>
        if (latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180) {
          Future.successful(Some(GeometryUtils.createPoint(latitude, longitude)))
        } else {
          Future.failed(InvalidCoordinatesException(latitude, longitude))
        }
      case (None, None) => Future.successful(None)
      case _ => Future.failed(InvalidCoordinatesException(
        lat.getOrElse(0.0), lon.getOrElse(0.0)
      ))
    }
  }
}