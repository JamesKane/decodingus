package services.mappers

import com.vividsolutions.jts.geom.{Coordinate, GeometryFactory}
import models.domain.genomics.{BiologicalSex, Biosample, BiosampleType, SpecimenDonor}
import models.domain.publications.{GenomicStudy, StudySource}
import services.ena.{EnaBiosampleData, EnaStudyData}
import services.ncbi.{SraBiosampleData, SraStudyData}

import java.util.UUID

object GenomicStudyMappers {
  private val ValidSexValues = Set("male", "female", "intersex")
  private val geometryFactory = new GeometryFactory()

  // Existing genomic study mapping methods remain unchanged
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

  case class BiosampleMappingResult(
                                     biosample: Biosample,
                                     specimenDonor: Option[SpecimenDonor]
                                   )

  def enaToBiosample(ena: EnaBiosampleData): BiosampleMappingResult = {
    val geoCoord = (ena.latitude, ena.longitude) match {
      case (Some(lat), Some(lon)) =>
        Some(geometryFactory.createPoint(new Coordinate(lon, lat)))
      case _ => None
    }

    val donorId = UUID.randomUUID().toString

    val specimenDonor = if (ena.sex.isDefined || geoCoord.isDefined) {
      Some(SpecimenDonor(
        id = None,
        donorIdentifier = donorId,
        originBiobank = ena.centerName,
        donorType = BiosampleType.Standard,
        sex = ena.sex.map(BiologicalSex.valueOf),
        geocoord = geoCoord,
        pgpParticipantId = None,
        atUri = None,
        dateRangeStart = None,
        dateRangeEnd = None
      ))
    } else None

    val biosample = Biosample(
      id = None,
      sampleGuid = UUID.randomUUID(),
      sampleAccession = ena.sampleAccession,
      description = ena.description,
      alias = ena.alias,
      centerName = ena.centerName,
      specimenDonorId = None, // Will be set after donor is created
      locked = false,
      sourcePlatform = None
    )

    BiosampleMappingResult(biosample, specimenDonor)
  }

  def sraToBiosample(sra: SraBiosampleData): BiosampleMappingResult = {
    val sex = validateSex(sra.attributes.get("sex"))
    val coordinates = for {
      lat <- sra.attributes.get("latitude")
        .orElse(sra.attributes.get("lat"))
        .flatMap(_.toDoubleOption)
      lon <- sra.attributes.get("longitude")
        .orElse(sra.attributes.get("lon"))
        .flatMap(_.toDoubleOption)
    } yield geometryFactory.createPoint(new Coordinate(lon, lat))

    val donorId = UUID.randomUUID().toString

    val specimenDonor = if (sex.isDefined || coordinates.isDefined) {
      Some(SpecimenDonor(
        id = None,
        donorIdentifier = donorId,
        originBiobank = sra.centerName,
        donorType = BiosampleType.Standard,
        sex = sex.map(BiologicalSex.valueOf),
        geocoord = coordinates,
        pgpParticipantId = None,
        atUri = None,
        dateRangeStart = None,
        dateRangeEnd = None
      ))
    } else None

    val biosample = Biosample(
      id = None,
      sampleGuid = UUID.randomUUID(),
      sampleAccession = sra.sampleAccession,
      description = sra.description,
      alias = sra.alias,
      centerName = sra.centerName,
      specimenDonorId = None, // Will be set after donor is created
      locked = false,
      sourcePlatform = None
    )

    BiosampleMappingResult(biosample, specimenDonor)
  }

  private def validateSex(sex: Option[String]): Option[String] = {
    sex.flatMap { s =>
      val normalized = s.toLowerCase.trim
      Some(normalized).filter(ValidSexValues.contains)
    }
  }
}