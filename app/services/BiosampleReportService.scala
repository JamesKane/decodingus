package services

import models.api.{BiosampleWithOrigin, GeoCoord}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class BiosampleReportService @Inject()(implicit ec: ExecutionContext) {
  def getBiosampleData(publicationId: Int): Future[Seq[BiosampleWithOrigin]] = Future {
    // Replace this with your actual data fetching from your internal system
    // This is just dummy data for demonstration
    val foo = GeoCoord(45.0, 90.0)
    publicationId match {
      case 1 => Seq(BiosampleWithOrigin("foo", "ERS123456", "H1", "tbd", Some(foo)), 
        BiosampleWithOrigin("bar", "ERS789012", "R2", "tbd", None))
      case 456 => Seq(BiosampleWithOrigin("baz", "DRS987654", "J1c", "tbd", Some(foo)))
      case _ => Seq.empty
    }
  }
}
