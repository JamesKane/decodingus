package services

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

case class Biosample(enaAccession: String, haplogroup: String)

@Singleton
class BiosampleReportService @Inject()(implicit ec: ExecutionContext) {
  def getBiosampleData(publicationId: Int): Future[Seq[Biosample]] = Future {
    // Replace this with your actual data fetching from your internal system
    // This is just dummy data for demonstration
    publicationId match {
      case 1 => Seq(Biosample("ERS123456", "H1"), Biosample("ERS789012", "R2"))
      case 456 => Seq(Biosample("DRS987654", "J1c"))
      case _ => Seq.empty
    }
  }
}
