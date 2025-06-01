package services.mappers

import com.vividsolutions.jts.geom.{Coordinate, GeometryFactory}
import models.domain.genomics.{Biosample, BiosampleType}
import models.domain.publications.{GenomicStudy, StudySource}
import services.ena.{EnaBiosampleData, EnaStudyData}
import services.ncbi.{SraBiosampleData, SraStudyData}

import java.util.UUID

/**
 * `GenomicStudyMappers` provides utility methods to map ENA and SRA study and biosample data 
 * into corresponding domain-specific representations (`GenomicStudy` and `Biosample`). 
 * The class handles transformations, setting default values, and validating input data 
 * during the mapping process.
 */
object GenomicStudyMappers {
  private val ValidSexValues = Set("male", "female", "intersex")
  private val geometryFactory = new GeometryFactory()

  /**
   * Converts an ENA (European Nucleotide Archive) study data object into a GenomicStudy object.
   *
   * @param ena The input data representing a study, encapsulated in an EnaStudyData object.
   * @return A GenomicStudy object containing the mapped data from the input EnaStudyData.
   */
  def enaToGenomicStudy(ena: EnaStudyData): GenomicStudy = GenomicStudy(
    id = None,
    accession = ena.accession,
    title = ena.title.take(255),
    centerName = ena.centerName,
    studyName = ena.studyName,
    details = ena.details,
    source = StudySource.ENA,
    submissionDate = None,
    bioProjectId = None,
    lastUpdate = None,
    molecule = None,
    topology = None,
    taxonomyId = None,
    version = None
  )

  /**
   * Converts a given SRA (Sequence Read Archive) study data object into a GenomicStudy object.
   *
   * @param sra The input data representing a study, encapsulated in an SraStudyData object.
   * @return A GenomicStudy object containing the mapped data from the input SraStudyData.
   */
  def sraToGenomicStudy(sra: SraStudyData): GenomicStudy = GenomicStudy(
    id = None,
    accession = sra.studyName,
    title = sra.title.take(255),
    centerName = sra.centerName,
    studyName = sra.studyName,
    details = sra.description,
    source = StudySource.NCBI_BIOPROJECT,
    submissionDate = None,
    bioProjectId = sra.bioProjectId,
    lastUpdate = None,
    molecule = None,
    topology = None,
    taxonomyId = None,
    version = None
  )

  /**
   * Converts an ENA (European Nucleotide Archive) biosample data object into a Biosample object.
   *
   * @param ena The input data representing a biosample, encapsulated in an EnaBiosampleData object.
   * @return A Biosample object containing the mapped data from the input EnaBiosampleData.
   */
  def enaToBiosample(ena: EnaBiosampleData): Biosample = {
    val geoCoord = (ena.latitude, ena.longitude) match {
      case (Some(lat), Some(lon)) =>
        Some(geometryFactory.createPoint(new Coordinate(lon, lat)))
      case _ => None
    }

    Biosample(
      id = None,
      sampleAccession = ena.sampleAccession,
      description = ena.description,
      alias = ena.alias,
      centerName = ena.centerName,
      sex = ena.sex,
      geocoord = geoCoord,
      specimenDonorId = None,
      sampleType = BiosampleType.Standard,
      sampleGuid = UUID.randomUUID()
    )
  }

  /**
   * Converts a given SRA (Sequence Read Archive) biosample data object into a Biosample object.
   *
   * @param sra The input data representing a biosample, encapsulated in an SraBiosampleData object.
   * @return A Biosample object containing the mapped data from the input SraBiosampleData.
   */
  def sraToBiosample(sra: SraBiosampleData): Biosample = {
    val sex = validateSex(sra.attributes.get("sex"))
    val coordinates = for {
      lat <- sra.attributes.get("latitude")
        .orElse(sra.attributes.get("lat"))
        .flatMap(_.toDoubleOption)
      lon <- sra.attributes.get("longitude")
        .orElse(sra.attributes.get("lon"))
        .flatMap(_.toDoubleOption)
    } yield geometryFactory.createPoint(new Coordinate(lon, lat))

    Biosample(
      id = None,
      sampleAccession = sra.sampleAccession,
      description = sra.description,
      alias = sra.alias,
      centerName = sra.centerName,
      sex = sex,
      geocoord = coordinates,
      specimenDonorId = None,
      sampleType = BiosampleType.Standard,
      sampleGuid = UUID.randomUUID()
    )
  }

  private def validateSex(sex: Option[String]): Option[String] = {
    sex.flatMap { s =>
      val normalized = s.toLowerCase.trim
      Some(normalized).filter(ValidSexValues.contains)
    }
  }
}