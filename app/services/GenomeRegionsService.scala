package services

import config.GenomicsConfig
import jakarta.inject.{Inject, Singleton}
import models.api.genomics.*
import models.domain.genomics.{GenbankContig, GenomeRegion}
import play.api.cache.AsyncCacheApi
import play.api.libs.json.{JsValue, Reads}
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
      
      // Group regions by contig name for efficient lookup.
      // We check for exact match or "chr"+name match to handle common naming conventions.
      val regionsByContig = data.regions.flatMap { region =>
        region.coordinates.get(canonicalName).map(coord => coord.contig -> region)
      }.groupBy(_._1).map { case (k, v) => k -> v.map(_._2) }

      val chromosomeMap = data.contigs.flatMap { contig =>
        contig.commonName.map { chromName =>
          // Try to find regions for this contig using common variations
          val relevantRegions = regionsByContig.getOrElse(chromName, Seq.empty) ++
            regionsByContig.getOrElse("chr" + chromName, Seq.empty) ++
            regionsByContig.getOrElse(chromName.replace("chr", ""), Seq.empty)
          
          // Deduplicate if needed (though mapping logic usually prevents duplicate keys in map unless source has duplicates)
          val uniqueRegions = relevantRegions.distinctBy(_.id)

          chromName -> buildChromosomeRegions(contig, uniqueRegions, canonicalName)
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
    buildName: String
  ): ChromosomeRegionsDto = {
    // Helper to convert to DTO with current build context
    def toDto(r: GenomeRegion): Option[RegionDto] = toRegionDto(r, buildName)
    
    // Extract specific region types
    val centromere = regions.find(_.regionType == "Centromere").flatMap(toDto)
    val telomereP = regions.find(_.regionType == "Telomere_P").flatMap(toDto)
    val telomereQ = regions.find(_.regionType == "Telomere_Q").flatMap(toDto)

    val telomeres = if (telomereP.isDefined || telomereQ.isDefined) {
      Some(TelomeresDto(p = telomereP, q = telomereQ))
    } else None

    // Cytobands
    val cytobands = regions.filter(_.regionType == "Cytoband")
      .flatMap(r => toCytobandDto(r, buildName))
      .sortBy(_.start)

    // Build Y-chromosome specific regions if this is chrY
    val yRegions = if (contig.commonName.exists(name => name.toLowerCase.contains("chry") || name == "Y")) {
      Some(buildYRegions(regions, buildName))
    } else None

    ChromosomeRegionsDto(
      length = contig.seqLength.toLong,
      centromere = centromere,
      telomeres = telomeres,
      cytobands = cytobands,
      regions = yRegions,
      strMarkers = Seq.empty // STR markers handled by separate service/table now
    )
  }

  /**
   * Build Y-chromosome specific regions grouped by type.
   */
  private def buildYRegions(regions: Seq[GenomeRegion], buildName: String): YChromosomeRegionsDto = {
    def toDto(r: GenomeRegion) = toRegionDto(r, buildName)
    def toNamedDto(r: GenomeRegion) = toNamedRegionDto(r, buildName)

    YChromosomeRegionsDto(
      par1 = regions.find(_.regionType == "PAR1").flatMap(toDto),
      par2 = regions.find(_.regionType == "PAR2").flatMap(toDto),
      xtr = regions.filter(_.regionType == "XTR").flatMap(toDto),
      ampliconic = regions.filter(_.regionType == "Ampliconic").flatMap(toDto),
      palindromes = regions.filter(_.regionType == "Palindrome").flatMap(toNamedDto),
      heterochromatin = regions.find(_.regionType == "Heterochromatin").flatMap(toDto),
      xDegenerate = regions.filter(_.regionType == "XDegenerate").flatMap(toDto)
    )
  }

  // Domain to DTO conversions

  private def getProperty[T](r: GenomeRegion, key: String)(implicit reads: Reads[T]): Option[T] = {
    (r.properties \ key).asOpt[T]
  }

  private def toRegionDto(r: GenomeRegion, buildName: String): Option[RegionDto] = {
    r.coordinates.get(buildName).map { coord =>
      RegionDto(
        start = coord.start,
        end = coord.end,
        `type` = Some(r.regionType),
        modifier = getProperty[Double](r, "modifier")
      )
    }
  }

  private def toNamedRegionDto(r: GenomeRegion, buildName: String): Option[NamedRegionDto] = {
    r.coordinates.get(buildName).map { coord =>
      NamedRegionDto(
        name = r.name.getOrElse(""),
        start = coord.start,
        end = coord.end,
        `type` = r.regionType,
        modifier = getProperty[Double](r, "modifier")
      )
    }
  }

  private def toCytobandDto(r: GenomeRegion, buildName: String): Option[CytobandDto] = {
    r.coordinates.get(buildName).map { coord =>
      CytobandDto(
        name = r.name.getOrElse(""),
        start = coord.start,
        end = coord.end,
        stain = getProperty[String](r, "stain").getOrElse("gneg")
      )
    }
  }
}