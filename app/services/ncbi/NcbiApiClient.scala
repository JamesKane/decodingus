package services.ncbi

import org.apache.pekko.stream.{Materializer, OverflowStrategy}
import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import play.api.Logging
import play.api.libs.json.{JsArray, JsObject, JsValue}
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

  private def makeRequest(request: WSRequest, retries: Int = 3): Future[WSResponse] = {
    request.get().flatMap { response =>
      (response.json \ "error").asOpt[String] match {
        case Some(error) if error.contains("API rate limit exceeded") && retries > 0 =>
          // Wait for 1.5 seconds before retrying (NCBI allows 3 requests per second)
          Thread.sleep(1500)
          makeRequest(request, retries - 1)
        case Some(error) =>
          Future.failed(NcbiRateLimitException(error))
        case None =>
          Future.successful(response)
      }
    }
  }


  def getSraStudyDetails(accession: String): Future[Option[SraStudyData]] = {
    if (accession.startsWith("PRJNA")) {
      // Direct BioProject query
      val bioProjectRequest = ws.url(s"$baseUrl/esummary.fcgi")
        .withQueryStringParameters(
          "db" -> "bioproject",
          "id" -> accession.substring(5), // Remove "PRJNA" prefix
          "retmode" -> "json"
        )

      makeRequest(bioProjectRequest).map { response =>
        for {
          result <- (response.json \ "result").asOpt[JsObject]
          data <- (result \ "uids").asOpt[JsArray].flatMap(_.value.headOption)
            .flatMap(uid => (result \ uid.as[String]).asOpt[JsObject])
        } yield {
          SraStudyData(
            title = (data \ "project_title").asOpt[String].getOrElse(""),
            centerName = (data \ "organization").asOpt[String].getOrElse("N/A"),
            studyName = accession,
            description = (data \ "project_description").asOpt[String].getOrElse(""),
            bioProjectId = Some(accession),
            biosampleIds = Seq.empty // We'll get these in a separate call
          )
        }
      }
    } else {
      // For SRA accessions, first get the BioProject ID, then get its details
      val searchRequest = ws.url(s"$baseUrl/esearch.fcgi")
        .withQueryStringParameters(
          "db" -> "sra",
          "term" -> accession,
          "retmode" -> "json"
        )

      makeRequest(searchRequest).flatMap { searchResponse =>
        val ids = (searchResponse.json \\ "idlist").headOption
          .map(_.as[Seq[String]])
          .getOrElse(Seq.empty)

        if (ids.isEmpty) {
          Future.successful(None)
        } else {
          // For SRA accessions, first get the BioProject ID, then get its details
          val searchRequest = ws.url(s"$baseUrl/esearch.fcgi")
            .withQueryStringParameters(
              "db" -> "sra",
              "term" -> accession,
              "retmode" -> "json"
            )

          makeRequest(searchRequest).flatMap { searchResponse =>
            val ids = (searchResponse.json \\ "idlist").headOption
              .map(_.as[Seq[String]])
              .getOrElse(Seq.empty)

            if (ids.isEmpty) {
              Future.successful(None)
            } else {
              val summaryRequest = ws.url(s"$baseUrl/esummary.fcgi")
                .withQueryStringParameters(
                  "db" -> "sra",
                  "id" -> ids.head,
                  "retmode" -> "json"
                )

              makeRequest(summaryRequest).flatMap { summaryResponse =>
                // Extract BioProject ID from the summary response
                val bioProjectIdOpt = for {
                  result <- (summaryResponse.json \ "result").asOpt[JsObject]
                  docsum <- result.value.get(ids.head).flatMap(_.asOpt[JsObject])
                  expXmlStr <- docsum.value.get("expxml").flatMap(_.asOpt[String])
                  xml = scala.xml.XML.loadString(s"<root>${expXmlStr.trim}</root>")
                  bioProjectId <- (xml \\ "Bioproject").headOption.map(_.text)
                } yield bioProjectId

                bioProjectIdOpt match {
                  case Some(bioProjectId) =>
                    // Add delay before recursive call
                    Thread.sleep(1500)
                    getSraStudyDetails(bioProjectId)
                  case None => Future.successful(None)
                }
              }
            }
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

                (xml \\ "Biosample").headOption.map(_.text).filter(_.nonEmpty).map { sampleAccession =>
                  val attributes = (xml \\ "Attributes" \\ "Attribute").map { attr =>
                    ((attr \ "@name").text, attr.text)
                  }.toMap

                  // Try to get sample name from various possible locations
                  val sampleName = (xml \\ "Sample" \ "@alias").headOption.map(_.text)
                    .orElse((xml \\ "Sample_Name").headOption.map(_.text))
                    .orElse((xml \\ "SAMPLE_NAME").headOption.map(_.text))
                    .orElse((xml \\ "Sample" \ "SAMPLE_NAME").headOption.map(_.text))

                  SraBiosampleData(
                    sampleAccession = sampleAccession,
                    description = (xml \\ "Summary" \\ "Title").headOption.map(_.text)
                      .getOrElse("No description available"),
                    alias = sampleName.orElse((xml \\ "Library_descriptor" \\ "LIBRARY_NAME").headOption.map(_.text)),
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