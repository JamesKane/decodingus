package services

import jakarta.inject.Inject
import models.domain.publications.Publication
import play.api.libs.json.{JsArray, JsValue}
import play.api.libs.ws.WSClient
import play.api.{Configuration, Logging}
import services.mappers.OpenAlexMapper

import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.inject.Singleton
import scala.concurrent.{ExecutionContext, Future}


/**
 * A service that interacts with OpenAlex API to fetch comprehensive publication data.
 *
 * @param configuration The application's configuration settings, used to retrieve the 'openalex.mailToEmail' value.
 * @param ws            The WSClient instance for making HTTP requests.
 * @param ec            An ExecutionContext, which provides context for executing code in a separate thread or reactor.
 */
@Singleton
class OpenAlexService @Inject()(
                                 configuration: Configuration,
                                 ws: WSClient)
                               (implicit ec: ExecutionContext) extends Logging {

  private val mailToEmail: String = configuration.get[String]("openalex.mailToEmail")
  private val openAlexBaseUrl: String = "https://api.openalex.org"

  /**
   * Fetches comprehensive publication data from OpenAlex for a given DOI
   * by making a single direct API call and parsing the JSON response.
   *
   * @param doi The Digital Object Identifier of the publication.
   * @return A Future containing an Option[Publication]. Returns None if the publication cannot be found or an error occurs.
   */
  def fetchAndMapPublicationByDOI(doi: String): Future[Option[Publication]] = {
    // Single API URL for the full work details
    val apiUrl = s"$openAlexBaseUrl/works/https://doi.org/$doi?mailto=$mailToEmail"

    logger.info(s"Fetching: $apiUrl")

    ws.url(apiUrl).get().map { response =>
      if (response.status == 200) {
        logger.debug(s"Successfully fetched JSON for DOI '$doi'")
        Some(OpenAlexMapper.jsonToPublication(response.json, doi))
      } else {
        logger.warn(s"OpenAlex API returned non-200 status for DOI '$doi': ${response.status}, Body: ${response.body}")
        None
      }
    }.recover {
      case e: Exception =>
        logger.error(s"Exception during OpenAlex API call for DOI '$doi': ${e.getMessage}", e)
        None
    }
  }

  /**
   * Searches for works in OpenAlex matching the given query string and filters.
   *
   * @param searchQuery The search term (maps to the 'search' query parameter).
   * @param filters     Optional filters (map of field -> value).
   * @return A Future containing a sequence of PublicationCandidate objects.
   */
  def searchWorks(searchQuery: String, filters: Map[String, String] = Map.empty): Future[Seq[models.domain.publications.PublicationCandidate]] = {
    val apiUrl = s"$openAlexBaseUrl/works"
    
    val queryParams = filters.toSeq :+ ("search" -> searchQuery) :+ ("mailto" -> mailToEmail)
    
    logger.info(s"Searching OpenAlex: $apiUrl with params $queryParams")

    ws.url(apiUrl).withQueryStringParameters(queryParams*).get().map { response =>
      if (response.status == 200) {
        (response.json \ "results").asOpt[Seq[JsValue]] match {
          case Some(results) =>
            results.map(OpenAlexMapper.jsonToPublicationCandidate)
          case None =>
            logger.warn("OpenAlex search response did not contain 'results' array.")
            Seq.empty
        }
      } else {
        logger.warn(s"OpenAlex search failed: ${response.status}, Body: ${response.body}")
        Seq.empty
      }
    }.recover {
      case e: Exception =>
        logger.error(s"Exception during OpenAlex search: ${e.getMessage}", e)
        Seq.empty
    }
  }
}
