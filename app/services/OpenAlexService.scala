package services

import jakarta.inject.Inject
import models.domain.publications.Publication
import play.api.libs.json.JsArray
import play.api.libs.ws.WSClient
import play.api.{Configuration, Logging}

import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.inject.Singleton
import scala.concurrent.{ExecutionContext, Future}


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

    logger.error(s"Fetching: $apiUrl")

    ws.url(apiUrl).get().map { response =>
      if (response.status == 200) {
        val json = response.json
        logger.debug(s"Successfully fetched JSON for DOI '$doi'")

        // Extract fields using Play's JSON combinators
        val openAlexId = (json \ "id").asOpt[String].map(_.split("/").last) // Extract just the ID part
        val pubmedId = (json \ "ids" \ "pmid").asOpt[String].map(_.replace("https://pubmed.ncbi.nlm.nih.gov/", ""))
        val title = (json \ "title").asOpt[String].getOrElse("Untitled") // Title is required in your model
        val doiFromApi = (json \ "doi").asOpt[String] // The DOI from the API itself

        // Authors
        val authors = (json \ "authorships").asOpt[JsArray].map { jsArray =>
          jsArray.value.flatMap { authorship =>
            (authorship \ "author" \ "display_name").asOpt[String]
          }.mkString(", ")
        }

        // Abstract (reconstruct from inverted index)
        val abstractSummary = (json \ "abstract_inverted_index").asOpt[Map[String, JsArray]].map { invertedIndex =>
          val wordsWithPositions = invertedIndex.flatMap { case (word, positionsArray) =>
            positionsArray.as[List[Int]].map(pos => (pos, word))
          }
          wordsWithPositions.toSeq.sortBy(_._1).map(_._2).mkString(" ")
        }

        // Journal / Source
        val journal = (json \ "primary_location" \ "source" \ "display_name").asOpt[String]

        // Publisher: Try primary_location first, then best_oa_location
        val publisherFromPrimary = (json \ "primary_location" \ "source" \ "host_organization_name").asOpt[String]
        val publisherFromBestOa = (json \ "best_oa_location" \ "source" \ "host_organization_name").asOpt[String]
        val publisher = publisherFromPrimary.orElse(publisherFromBestOa)

        // Publication Date
        val publicationDate = (json \ "publication_date").asOpt[String].flatMap { dateString =>
          try {
            Some(LocalDate.parse(dateString))
          } catch {
            case e: DateTimeParseException =>
              logger.warn(s"Failed to parse publication_date '$dateString' for DOI '$doi': ${e.getMessage}")
              None
          }
        }

        // URLs and Open Access Info
        val openAccessStatus = (json \ "open_access" \ "oa_status").asOpt[String]
        val openAccessUrl = (json \ "best_oa_location" \ "pdf_url").asOpt[String]
        val determinedUrl = openAccessUrl.orElse(doiFromApi.map(d => s"https://doi.org/$d"))

        // Citation Metrics
        val citedByCount = (json \ "cited_by_count").asOpt[Int]
        val citationNormalizedPercentile = (json \ "citation_normalized_percentile" \ "value").asOpt[Float]

        // Primary Topic
        val primaryTopic = (json \ "primary_topic" \ "display_name").asOpt[String]

        // Publication Type
        val publicationType = (json \ "type").asOpt[String]

        Some(Publication(
          id = None,
          openAlexId = openAlexId,
          pubmedId = pubmedId,
          doi = doiFromApi, // Use the DOI parsed from the API response
          title = title,
          authors = authors,
          abstractSummary = abstractSummary,
          journal = journal,
          publicationDate = publicationDate,
          url = determinedUrl,
          citationNormalizedPercentile = citationNormalizedPercentile,
          citedByCount = citedByCount,
          openAccessStatus = openAccessStatus,
          openAccessUrl = openAccessUrl,
          primaryTopic = primaryTopic,
          publicationType = publicationType,
          publisher = publisher
        ))
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
}
