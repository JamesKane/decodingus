package services

import config.GenomicsConfig
import jakarta.inject.{Inject, Singleton}
import models.api.genomics.*
import models.domain.genomics.{GenomeRegion, GenbankContig}
import play.api.cache.AsyncCacheApi
import repositories.{FullBuildData, GenomeRegionsRepository}

import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

/**
 * Service for the Genome Regions API.
 * Provides curated genomic region metadata for reference builds.
 */
@Singleton
class GenomeRegionsService @Inject()(
  genomeRegionsRepository: GenomeRegionsRepository,
  genomicsConfig: GenomicsConfig,
  cache: AsyncCacheApi
)(implicit ec: ExecutionContext) {

  private val CacheDuration = 7.days

  /**
   * Get genome regions for a build, resolving aliases.
   */
  def getRegions(buildName: String): Future[Either[GenomeRegionsError, GenomeRegionsResponse]] = {
    val canonicalName = genomicsConfig.resolveReferenceName(buildName)

    if (!genomicsConfig.supportedReferences.contains(canonicalName)) {
      Future.successful(Left(GenomeRegionsError(
        error = "Unknown build",
        message = s"Build '$buildName' is not supported. Supported builds: ${genomicsConfig.supportedReferences.mkString(", ")}",
        supportedBuilds = genomicsConfig.supportedReferences
      )))
    } else {
      val cacheKey = s"genome-regions:$canonicalName"
      cache.getOrElseUpdate(cacheKey, CacheDuration) {
        buildResponse(canonicalName).map(Right(_))
      }
    }
  }

  /**
   * Get the ETag for a build's data.
   */
  def getETag(buildName: String): Future[Option[String]] = {
    val canonicalName = genomicsConfig.resolveReferenceName(buildName)
    genomeRegionsRepository.getVersion(canonicalName).map { versionOpt =>
      versionOpt.map { version =>
        generateETag(canonicalName, version.dataVersion)
      }
    }
  }

  /**
   * Generate ETag from build name and version.
   */
  def generateETag(buildName: String, dataVersion: String): String = {
    val input = s"$buildName:$dataVersion"
    val md5 = MessageDigest.getInstance("MD5")
    val hash = md5.digest(input.getBytes("UTF-8")).map("%02x".format(_)).mkString
    s""""$hash""""
  }

  /**
   * Get list of supported builds.
   */
  def getSupportedBuilds: Seq[String] = genomicsConfig.supportedReferences

  /**
   * Build the full response for a reference genome.
   */
  private def buildResponse(canonicalName: String): Future[GenomeRegionsResponse] = {
    genomeRegionsRepository.getFullBuildData(canonicalName).map { data =>
      val chromosomeMap = data.contigs.flatMap { contig =>
        contig.commonName.map { chromName =>
          val contigId = contig.id.getOrElse(0)
          val regions = data.regions.getOrElse(contigId, Seq.empty)
          val cytobands = data.cytobands.getOrElse(contigId, Seq.empty)
          val markers = data.strMarkers.getOrElse(contigId, Seq.empty)

          chromName -> buildChromosomeRegions(contig, regions, cytobands, markers)
        }
      }.toMap

      GenomeRegionsResponse(
        build = canonicalName,
        version = data.version.map(_.dataVersion).getOrElse("unknown"),
        generatedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
        chromosomes = chromosomeMap
      )
    }
  }

  /**
   * Build the chromosome regions DTO from domain models.
   */
  private def buildChromosomeRegions(
    contig: GenbankContig,
    regions: Seq[GenomeRegion],
    cytobands: Seq[models.domain.genomics.Cytoband],
    markers: Seq[models.domain.genomics.StrMarker]
  ): ChromosomeRegionsDto = {
    // Extract specific region types
    val centromere = regions.find(_.regionType == "Centromere").map(toRegionDto)
    val telomereP = regions.find(_.regionType == "Telomere_P").map(toRegionDto)
    val telomereQ = regions.find(_.regionType == "Telomere_Q").map(toRegionDto)

    val telomeres = if (telomereP.isDefined || telomereQ.isDefined) {
      Some(TelomeresDto(p = telomereP, q = telomereQ))
    } else None

    // Build Y-chromosome specific regions if this is chrY
    val yRegions = if (contig.commonName.exists(name => name.toLowerCase.contains("chry") || name == "Y")) {
      Some(buildYRegions(regions))
    } else None

    ChromosomeRegionsDto(
      length = contig.seqLength.toLong,
      centromere = centromere,
      telomeres = telomeres,
      cytobands = cytobands.map(toCytobandDto),
      regions = yRegions,
      strMarkers = markers.map(toStrMarkerDto)
    )
  }

  /**
   * Build Y-chromosome specific regions grouped by type.
   */
  private def buildYRegions(regions: Seq[GenomeRegion]): YChromosomeRegionsDto = {
    YChromosomeRegionsDto(
      par1 = regions.find(_.regionType == "PAR1").map(toRegionDto),
      par2 = regions.find(_.regionType == "PAR2").map(toRegionDto),
      xtr = regions.filter(_.regionType == "XTR").map(toRegionDto),
      ampliconic = regions.filter(_.regionType == "Ampliconic").map(toRegionDto),
      palindromes = regions.filter(_.regionType == "Palindrome").map(toNamedRegionDto),
      heterochromatin = regions.find(_.regionType == "Heterochromatin").map(toRegionDto),
      xDegenerate = regions.filter(_.regionType == "XDegenerate").map(toRegionDto)
    )
  }

  // Domain to DTO conversions
  private def toRegionDto(r: GenomeRegion): RegionDto =
    RegionDto(r.startPos, r.endPos, Some(r.regionType), r.modifier.map(_.toDouble))

  private def toNamedRegionDto(r: GenomeRegion): NamedRegionDto =
    NamedRegionDto(r.name.getOrElse(""), r.startPos, r.endPos, r.regionType, r.modifier.map(_.toDouble))

  private def toCytobandDto(c: models.domain.genomics.Cytoband): CytobandDto =
    CytobandDto(c.name, c.startPos, c.endPos, c.stain)

  private def toStrMarkerDto(s: models.domain.genomics.StrMarker): StrMarkerDto =
    StrMarkerDto(s.name, s.startPos, s.endPos, s.period, s.verified, s.note)
}
