package services

import models.api.BiosampleWithOrigin

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class BiosampleReportService @Inject()(implicit ec: ExecutionContext) {
  def getBiosampleData(publicationId: Int): Future[Seq[BiosampleWithOrigin]] = Future {
    // Replace this with your actual data fetching from your internal system
    // This is just dummy data for demonstration
    publicationId match {
      case 1 => Seq(BiosampleWithOrigin("foo", "ERS123456", "H1", "tbd", Some(45.0), Some(90.0)), BiosampleWithOrigin("bar", "ERS789012", "R2", "tbd", Some(45.0), Some(90.0)))
      case 456 => Seq(BiosampleWithOrigin("baz", "DRS987654", "J1c", "tbd", Some(45.0), Some(90.0)))
      case _ => Seq.empty
    }
  }
}
