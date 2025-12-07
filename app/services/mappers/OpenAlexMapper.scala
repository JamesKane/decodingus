package services.mappers

import models.domain.publications.Publication
import play.api.Logging
import play.api.libs.json.{JsArray, JsValue}

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Object responsible for mapping OpenAlex JSON data to domain models.
 * Provides methods to extract structured information and transform
 * the JSON into a `Publication` object with relevant details.
 */
object OpenAlexMapper extends Logging {
  private case class BasicInfo(
                                openAlexId: Option[String],
                                pubmedId: Option[String],
                                title: String
                              )

  private case class PublishingInfo(
                                     journal: Option[String],
                                     publisher: Option[String],
                                     publicationDate: Option[LocalDate]
                                   )

  private case class AccessInfo(
                                 openAccessStatus: Option[String],
                                 openAccessUrl: Option[String],
                                 determinedUrl: Option[String]
                               )

  private case class Metrics(
                              citedByCount: Option[Int],
                              citationNormalizedPercentile: Option[Float]
                            )

  private case class ClassificationInfo(
                                         primaryTopic: Option[String],
                                         publicationType: Option[String]
                                       )

  private def extractBasicInfo(json: JsValue): BasicInfo = {
    BasicInfo(
      openAlexId = (json \ "id").asOpt[String].map(_.split("/").last),
      pubmedId = (json \ "ids" \ "pmid").asOpt[String].map(_.replace("https://pubmed.ncbi.nlm.nih.gov/", "")),
      title = (json \ "title").asOpt[String].getOrElse("Untitled")
    )
  }

  private def extractAuthors(json: JsValue): Option[String] = {
    (json \ "authorships").asOpt[JsArray].map { jsArray =>
      val authors = jsArray.value.flatMap { authorship =>
        (authorship \ "author" \ "display_name").asOpt[String]
      }
      authors.toList match {
        case Nil => "No authors listed"
        case List(a) => a
        case List(a, b) => s"$a and $b"
        case List(a, b, c) => s"$a, $b and $c"
        case List(a, b, c, _*) => s"$a, $b, $c et al."
      }
    }
  }

  private def extractAbstract(json: JsValue): Option[String] = {
    (json \ "abstract_inverted_index").asOpt[Map[String, JsArray]].map { invertedIndex =>
      val wordsWithPositions = invertedIndex.flatMap { case (word, positionsArray) =>
        positionsArray.as[List[Int]].map(pos => (pos, word))
      }
      wordsWithPositions.toSeq.sortBy(_._1).map(_._2).mkString(" ")
    }
  }

  private def extractPublishingInfo(json: JsValue, doi: String): PublishingInfo = {
    val journal = (json \ "primary_location" \ "source" \ "display_name").asOpt[String]

    val publisherFromPrimary = (json \ "primary_location" \ "source" \ "host_organization_name").asOpt[String]
    val publisherFromBestOa = (json \ "best_oa_location" \ "source" \ "host_organization_name").asOpt[String]
    val publisher = publisherFromPrimary.orElse(publisherFromBestOa)

    val publicationDate = (json \ "publication_date").asOpt[String].flatMap { dateString =>
      try {
        Some(LocalDate.parse(dateString))
      } catch {
        case e: DateTimeParseException =>
          logger.warn(s"Failed to parse publication_date '$dateString' for DOI '$doi': ${e.getMessage}")
          None
      }
    }

    PublishingInfo(journal, publisher, publicationDate)
  }

  private def extractAccessInfo(json: JsValue, doi: Option[String]): AccessInfo = {
    val openAccessStatus = (json \ "open_access" \ "oa_status").asOpt[String]
    val openAccessUrl = (json \ "best_oa_location" \ "pdf_url").asOpt[String]
    val determinedUrl = openAccessUrl.orElse(doi.map(d => s"https://doi.org/$d"))

    AccessInfo(openAccessStatus, openAccessUrl, determinedUrl)
  }

  private def extractMetrics(json: JsValue): Metrics = {
    Metrics(
      citedByCount = (json \ "cited_by_count").asOpt[Int],
      citationNormalizedPercentile = (json \ "citation_normalized_percentile" \ "value").asOpt[Float]
    )
  }

  private def extractClassification(json: JsValue): ClassificationInfo = {
    ClassificationInfo(
      primaryTopic = (json \ "primary_topic" \ "display_name").asOpt[String],
      publicationType = (json \ "type").asOpt[String]
    )
  }

  /**
   * Converts a JSON representation of a publication and its DOI into a `Publication` object.
   *
   * @param json The JSON structure containing the publication data.
   * @param doi  A string representing the DOI (Digital Object Identifier) of the publication.
   * @return A `Publication` object populated with data extracted from the provided JSON and DOI.
   */
  def jsonToPublication(json: JsValue, doi: String): Publication = {
    val basicInfo = extractBasicInfo(json)
    val authors = extractAuthors(json)
    val abstractSummary = extractAbstract(json)
    val publishingInfo = extractPublishingInfo(json, doi)
    val accessInfo = extractAccessInfo(json, Some(doi))
    val metrics = extractMetrics(json)
    val classification = extractClassification(json)

    Publication(
      id = None,
      openAlexId = basicInfo.openAlexId,
      pubmedId = basicInfo.pubmedId,
      doi = Some(doi),
      title = basicInfo.title,
      authors = authors,
      abstractSummary = abstractSummary,
      journal = publishingInfo.journal,
      publicationDate = publishingInfo.publicationDate,
      url = accessInfo.determinedUrl,
      citationNormalizedPercentile = metrics.citationNormalizedPercentile,
      citedByCount = metrics.citedByCount,
      openAccessStatus = accessInfo.openAccessStatus,
      openAccessUrl = accessInfo.openAccessUrl,
      primaryTopic = classification.primaryTopic,
      publicationType = classification.publicationType,
      publisher = publishingInfo.publisher
    )
  }
}