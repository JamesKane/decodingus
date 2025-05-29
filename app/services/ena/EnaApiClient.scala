package services.ena

import org.apache.pekko.stream.Materializer
import play.api.Logging
import play.api.libs.json.{JsArray, JsValue}
import play.api.libs.ws.WSClient

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

case class EnaStudyData(
                         accession: String,
                         title: String,
                         centerName: String,
                         studyName: String,
                         details: String
                       )

case class EnaBiosampleData(
                             sampleAccession: String,
                             description: String,
                             alias: Option[String],
                             centerName: String,
                             sex: Option[String],
                             latitude: Option[Double],
                             longitude: Option[Double],
                             collectionDate: Option[String]
                           )

@Singleton
class EnaApiClient @Inject()(ws: WSClient)(implicit ec: ExecutionContext, mat: Materializer) extends Logging {
  private val enaPortalApiBaseUrl = "https://www.ebi.ac.uk/ena/portal/api/search"
  private val ValidSexValues = Set("male", "female", "intersex")

  def getStudyDetails(accession: String): Future[Option[EnaStudyData]] = {
    val query = s"study_accession=$accession"
    val fields = "study_accession,study_title,center_name,study_name,study_description"

    ws.url(enaPortalApiBaseUrl)
      .withQueryStringParameters(
        "result" -> "study",
        "query" -> query,
        "fields" -> fields,
        "format" -> "json"
      )
      .get()
      .map { response =>
        response.status match {
          case 200 =>
            val jsonArray = response.json.as[JsArray]
            jsonArray.value.headOption.map { studyJson =>
              EnaStudyData(
                accession = (studyJson \ "study_accession").as[String],
                title = (studyJson \ "study_title").as[String],
                centerName = (studyJson \ "center_name").asOpt[String].getOrElse("N/A"),
                studyName = (studyJson \ "study_name").asOpt[String].getOrElse("N/A"),
                details = (studyJson \ "study_description").asOpt[String].getOrElse("")
              )
            }
          case _ =>
            logger.error(s"Error fetching ENA study $accession: ${response.status} - ${response.body}")
            None
        }
      }
      .recover {
        case e: Exception =>
          logger.error(s"Exception during ENA API call for $accession: $e")
          None
      }
  }

  def getBiosamples(studyAccession: String): Future[Seq[EnaBiosampleData]] = {
    val fields = "sample_accession,description,sample_alias,center_name,sex,lat,lon,collection_date"

    ws.url(enaPortalApiBaseUrl)
      .withQueryStringParameters(
        "result" -> "sample",
        "query" -> s"study_accession=$studyAccession",
        "fields" -> fields,
        "format" -> "json",
        "limit" -> "0"
      )
      .get()
      .map { response =>
        response.status match {
          case 200 =>
            val jsonArray = response.json.as[JsArray]
            jsonArray.value.map { sampleJson =>
              EnaBiosampleData(
                sampleAccession = (sampleJson \ "sample_accession").as[String],
                description = (sampleJson \ "description").asOpt[String].getOrElse(""),
                alias = (sampleJson \ "sample_alias").asOpt[String],
                centerName = (sampleJson \ "center_name").asOpt[String].getOrElse("N/A"),
                sex = (sampleJson \ "sex").asOpt[String].flatMap(validateSex),
                latitude = (sampleJson \ "lat").asOpt[String].flatMap(_.toDoubleOption),
                longitude = (sampleJson \ "lon").asOpt[String].flatMap(_.toDoubleOption),
                collectionDate = (sampleJson \ "collection_date").asOpt[String]
              )
            }.toSeq
          case _ =>
            logger.error(s"Error fetching ENA samples for study $studyAccession: ${response.status} - ${response.body}")
            Seq.empty
        }
      }
      .recover {
        case e: Exception =>
          logger.error(s"Exception during ENA samples API call for $studyAccession: $e")
          Seq.empty
      }
  }

  private def validateSex(sex: String): Option[String] = {
    val normalized = sex.toLowerCase.trim
    Some(normalized).filter(ValidSexValues.contains)
  }
}