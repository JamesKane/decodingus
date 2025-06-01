package services

import models.domain.genomics.Biosample
import models.domain.publications.{GenomicStudy, StudySource}
import play.api.Logging
import play.api.libs.ws.*
import services.ena.{EnaApiClient, EnaBiosampleData, EnaStudyData}
import services.mappers.GenomicStudyMappers
import services.ncbi.{NcbiApiClient, SraBiosampleData, SraStudyData}

import javax.inject.*
import scala.concurrent.{ExecutionContext, Future}

/**
 * Service responsible for retrieving and mapping genomic study details from external APIs.
 * This class interacts with external resources such as ENA and NCBI to fetch and process genomic study data.
 *
 * @constructor Creates a new instance of `GenomicStudyService` with dependency-injected clients and execution context.
 * @param ws            The `WSClient` used for making HTTP requests to external APIs.
 * @param ncbiApiClient The client for interacting with NCBI APIs.
 * @param enaApiClient  The client for interacting with ENA APIs.
 * @param ec            The execution context for asynchronous operations.
 */
@Singleton
class GenomicStudyService @Inject()(
                                     ws: WSClient,
                                     ncbiApiClient: NcbiApiClient,
                                     enaApiClient: EnaApiClient
                                   )(implicit ec: ExecutionContext) extends Logging {

  // ENA Browser API for XML (often more detailed for studies)
  // For JSON, use the ENA Portal API
  private val ncbiEutilsBaseUrl = "https://eutils.ncbi.nlm.nih.gov/entrez/eutils"

  private val ValidSexValues = Set("male", "female", "intersex")

  /**
   * Fetches detailed information about a genomic study based on its accession.
   * Determines the source of the study and retrieves the information accordingly.
   *
   * @param accession The unique accession identifier for the genomic study.
   *                  It determines the study source (e.g., ENA, NCBI BioProject, NCBI GenBank).
   * @return A Future containing an Option of GenomicStudy. The option is None if no study details are found.
   *         The GenomicStudy provides detailed metadata about the study, including accession, title, center name, etc.
   */
  def getStudyDetails(accession: String): Future[Option[GenomicStudy]] = {
    determineSource(accession) match {
      case StudySource.ENA => enaApiClient.getStudyDetails(accession)
        .map(_.map(GenomicStudyMappers.enaToGenomicStudy))
      case StudySource.NCBI_BIOPROJECT => ncbiApiClient.getSraStudyDetails(accession)
        .map(_.map(GenomicStudyMappers.sraToGenomicStudy))
      case StudySource.NCBI_GENBANK => getGenbankDetails(accession)
    }
  }

  private def determineSource(accession: String): StudySource = {
    val acc = accession.toUpperCase.trim

    acc match {
      // NCBI BioProjects
      case a if a.startsWith("PRJNA") => StudySource.NCBI_BIOPROJECT
      // ENA BioProjects
      case a if a.startsWith("PRJEB") => StudySource.ENA
      // ENA SRA Accessions
      case a if List("ERR", "ERX", "ERS", "ERA", "ERZ", "ERP").exists(a.startsWith) => StudySource.ENA
      // NCBI SRA Accessions
      case a if List("SRR", "SRX", "SRS", "SRP").exists(a.startsWith) => StudySource.NCBI_BIOPROJECT
      // NCBI RefSeq Accessions
      case a if List("NM_", "NP_", "XM_", "XP_", "NR_", "XR_", "WP_").exists(a.startsWith) => StudySource.NCBI_GENBANK
      // Common NCBI GenBank patterns
      case a if (
        // Single letter + 5 digits
        (a.length == 6 && a.head.isLetter && a.tail.forall(_.isDigit)) ||
          // Two letters + 6 digits
          (a.length == 8 && a.take(2).forall(_.isLetter) && a.drop(2).forall(_.isDigit)) ||
          // WGS pattern: four letters + "01" + 6 digits
          (a.length >= 12 &&
            a.take(4).forall(_.isLetter) &&
            a.slice(4, 6) == "01" &&
            a.drop(6).forall(_.isDigit))
        ) => StudySource.NCBI_GENBANK
      // Handle versioned accessions
      case a if a.contains(".") =>
        a.split("\\.") match {
          case Array(base, version) if version.forall(_.isDigit) => StudySource.NCBI_GENBANK
          case _ => StudySource.ENA // Default for unrecognized patterns
        }
      // Default to ENA for unrecognized patterns
      case _ => StudySource.ENA
    }
  }

  /**
   * Retrieves a list of biosample metadata associated with a given study accession.
   * Determines the source of the study and invokes the appropriate data retrieval method.
   *
   * @param studyAccession The unique accession identifier for the study. This determines the study source
   *                       (e.g., ENA, NCBI BioProject) from which biosample data is retrieved.
   * @return A Future containing a sequence of Biosample objects. If no biosamples are found or the source
   *         is unrecognized, the sequence will be empty.
   */
  def getBiosamplesForStudy(studyAccession: String): Future[Seq[Biosample]] = {
    determineSource(studyAccession) match {
      case StudySource.ENA =>
        enaApiClient.getBiosamples(studyAccession)
          .map(_.map(GenomicStudyMappers.enaToBiosample))
      case StudySource.NCBI_BIOPROJECT =>
        ncbiApiClient.getSraBiosamples(studyAccession)
          .map(_.map(GenomicStudyMappers.sraToBiosample))
      case _ =>
        Future.successful(Seq.empty)
    }
  }

  private def getGenbankDetails(accession: String): Future[Option[GenomicStudy]] = {
    val url = s"$ncbiEutilsBaseUrl/efetch.fcgi"

    ws.url(url)
      .withQueryStringParameters(
        "db" -> "nucleotide",
        "id" -> accession,
        "rettype" -> "gb",
        "retmode" -> "xml"
      )
      .get()
      .map { response =>
        response.status match {
          case 200 =>
            try {
              val xml = scala.xml.XML.loadString(response.body)

              // Extract GBSeq elements
              val seqElement = xml \\ "GBSeq"

              seqElement.headOption.map { seq =>
                val references = parseReferences(seq \\ "GBReference")

                GenomicStudy(
                  id = None,
                  accession = (seq \\ "GBSeq_accession-version").text,
                  title = (seq \\ "GBSeq_definition").text,
                  centerName = (seq \\ "GBSeq_source").text,
                  studyName = (seq \\ "GBSeq_locus").text,
                  details = (seq \\ "GBSeq_comment").text,
                  source = StudySource.NCBI_GENBANK,
                  submissionDate = Some(parseGenbankDate((seq \\ "GBSeq_create-date").text)),
                  lastUpdate = Some(parseGenbankDate((seq \\ "GBSeq_update-date").text)),
                  molecule = Some((seq \\ "GBSeq_moltype").text),
                  topology = Some((seq \\ "GBSeq_topology").text),
                  taxonomyId = (seq \\ "GBSeq_taxonomy-id").headOption.map(_.text.toInt),
                  version = Some((seq \\ "GBSeq_accession-version").text.split("\\.")(1))
                )
              }
            } catch {
              case e: Exception =>
                logger.error(s"Error parsing GenBank XML for $accession: ${e.getMessage}")
                None
            }
          case status =>
            logger.error(s"Error fetching GenBank entry $accession: $status - ${response.body}")
            None
        }
      }
      .recover {
        case e: Exception =>
          logger.error(s"Exception during GenBank API call for $accession: $e")
          None
      }
  }

  private def parseGenbankDate(date: String): java.time.LocalDate = {
    // GenBank dates are in format "DD-MMM-YYYY"
    java.time.LocalDate.parse(
      date,
      java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yyyy", java.util.Locale.ENGLISH)
    )
  }

  private def parseReferences(refs: scala.xml.NodeSeq): Seq[Map[String, String]] = {
    refs.map { ref =>
      Map(
        "authors" -> (ref \\ "GBReference_authors" \\ "GBAuthor").map(_.text).mkString(", "),
        "title" -> (ref \\ "GBReference_title").text,
        "journal" -> (ref \\ "GBReference_journal").text,
        "pubmed" -> (ref \\ "GBReference_pubmed").text
      ).filter(_._2.nonEmpty) // Remove empty values
    }.toSeq
  }

}