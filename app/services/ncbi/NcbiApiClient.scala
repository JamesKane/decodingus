package services.ncbi

import org.apache.pekko.stream.{Materializer, OverflowStrategy}
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import play.api.Logging
import play.api.libs.json.{JsObject, JsValue}
import play.api.libs.ws.{WSClient, WSRequest, WSResponse}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

case class NcbiRateLimitException(message: String) extends Exception(message)

case class SraStudyData(
                         title: String,
                         centerName: String,
                         studyName: String,
                         description: String,
                         bioProjectId: Option[String],
                         biosampleIds: Seq[String]
                       )

case class SraBiosampleData(
                             sampleAccession: String,
                             description: String,
                             alias: Option[String],
                             centerName: String,
                             attributes: Map[String, String]
                           )

@Singleton
class NcbiApiClient @Inject()(ws: WSClient)(implicit ec: ExecutionContext, mat: Materializer) extends Logging {
  private val baseUrl = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils"

  // Create a queue that processes requests with rate limiting
  private val (queue, _) = Source.queue[(WSRequest, Promise[WSResponse])](
      bufferSize = 100,
      overflowStrategy = OverflowStrategy.backpressure
    ).throttle(2, 1.second) // NCBI's limit
    .mapAsync(1) { case (request, promise) =>
      request.get()
        .map { response =>
          if (response.status == 429) {
            promise.failure(NcbiRateLimitException(response.body))
            throw NcbiRateLimitException(response.body)
          } else {
            promise.success(response)
            response
          }
        }
        .recover { case e =>
          promise.failure(e)
          throw e
        }
    }
    .toMat(Sink.ignore)(Keep.both)
    .run()

  private def makeRequest(request: WSRequest): Future[WSResponse] = {
    val promise = Promise[WSResponse]()
    queue.offer((request, promise)).flatMap {
      case org.apache.pekko.stream.QueueOfferResult.Enqueued => promise.future
      case other =>
        Future.failed(new Exception(s"Failed to enqueue request: $other"))
    }
  }

  def getSraStudyDetails(accession: String): Future[Option[SraStudyData]] = {
    val searchRequest = ws.url(s"$baseUrl/esearch.fcgi")
      .withQueryStringParameters(
        "db" -> "sra",
        "term" -> accession,
        "retmode" -> "json"
      )

    // First get the SRA ID
    makeRequest(searchRequest).flatMap { searchResponse =>
      val ids = (searchResponse.json \\ "idlist").headOption
        .map(_.as[Seq[String]])
        .getOrElse(Seq.empty)

      if (ids.isEmpty) {
        Future.successful(None)
      } else {
        // Then get the details
        val summaryRequest = ws.url(s"$baseUrl/esummary.fcgi")
          .withQueryStringParameters(
            "db" -> "sra",
            "id" -> ids.head,
            "retmode" -> "json"
          )

        makeRequest(summaryRequest).map { summaryResponse =>
          for {
            result <- (summaryResponse.json \ "result").asOpt[JsObject]
            docsum <- result.value.get(ids.head).flatMap(_.asOpt[JsObject])
            expXmlStr <- docsum.value.get("expxml").flatMap(_.asOpt[String])
            xml = scala.xml.XML.loadString(s"<root>${expXmlStr.trim}</root>")
          } yield {
            val title = (xml \\ "Summary" \\ "Title").headOption.map(_.text).getOrElse("")
            val centerName = (xml \\ "Submitter" \\ "@center_name").headOption.map(_.text).getOrElse("N/A")
            val studyName = (xml \\ "Study" \\ "@name").headOption.map(_.text).getOrElse(accession)
            val bioProjectId = (xml \\ "Bioproject").headOption.map(_.text)
            val biosampleIds = (xml \\ "Biosample").map(_.text)

            SraStudyData(
              title = title,
              centerName = centerName,
              studyName = studyName,
              description = title, // Often the title is the best description we have from SRA
              bioProjectId = bioProjectId,
              biosampleIds = biosampleIds
            )
          }
        }
      }
    }
  }

  def getSraBiosamples(accession: String): Future[Seq[SraBiosampleData]] = {
    val searchRequest = ws.url(s"$baseUrl/esearch.fcgi")
      .withQueryStringParameters(
        "db" -> "sra",
        "term" -> s"$accession[BioProject]",
        "retmode" -> "json"
      )

    makeRequest(searchRequest).flatMap { searchResponse =>
      val ids = (searchResponse.json \\ "idlist").headOption
        .map(_.as[Seq[String]])
        .getOrElse(Seq.empty)

      if (ids.isEmpty) {
        Future.successful(Seq.empty)
      } else {
        // Get all experiment details in one call
        val summaryRequest = ws.url(s"$baseUrl/esummary.fcgi")
          .withQueryStringParameters(
            "db" -> "sra",
            "id" -> ids.mkString(","),
            "retmode" -> "json"
          )

        makeRequest(summaryRequest).map { summaryResponse =>
          val result = for {
            resultObj <- (summaryResponse.json \ "result").asOpt[JsObject]
            // Remove the uids key which contains duplicate data
            experiments = resultObj.value.view.filterKeys(_ != "uids").toMap
          } yield {
            experiments.flatMap { case (_, expJson) =>
              try {
                val expXmlStr = (expJson \ "expxml").as[String]
                val xml = scala.xml.XML.loadString(s"<root>${expXmlStr.trim}</root>")

                // Only create a Biosample if we have a valid sample accession
                (xml \\ "Biosample").headOption.map(_.text).filter(_.nonEmpty).map { sampleAccession =>
                  val attributes = (xml \\ "Attributes" \\ "Attribute").map { attr =>
                    ((attr \ "@name").text, attr.text)
                  }.toMap

                  SraBiosampleData(
                    sampleAccession = sampleAccession,
                    description = (xml \\ "Summary" \\ "Title").headOption.map(_.text)
                      .getOrElse("No description available"),
                    alias = (xml \\ "Library_descriptor" \\ "LIBRARY_NAME").headOption.map(_.text),
                    centerName = (xml \\ "Submitter" \\ "@center_name").headOption.map(_.text)
                      .getOrElse("N/A"),
                    attributes = attributes
                  )
                }
              } catch {
                case e: Exception =>
                  logger.error(s"Error parsing experiment XML: ${e.getMessage}")
                  None
              }
            }
          }

          result.getOrElse(Seq.empty).toSeq
        }
      }
    }
  }
}