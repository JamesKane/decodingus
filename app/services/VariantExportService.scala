package services

import jakarta.inject.{Inject, Singleton}
import models.api.*
import models.domain.genomics.VariantGroup
import play.api.{Configuration, Logging}
import play.api.libs.json.Json
import repositories.{HaplogroupVariantRepository, VariantAliasRepository, VariantRepository}

import java.io.{BufferedOutputStream, FileOutputStream, OutputStreamWriter}
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.util.zip.GZIPOutputStream
import scala.concurrent.{ExecutionContext, Future}

/**
 * Service for generating bulk variant export files.
 * Creates a gzipped JSONL file containing all variants for Edge App consumption.
 */
@Singleton
class VariantExportService @Inject()(
                                      variantRepository: VariantRepository,
                                      variantAliasRepository: VariantAliasRepository,
                                      haplogroupVariantRepository: HaplogroupVariantRepository,
                                      configuration: Configuration
                                    )(implicit ec: ExecutionContext) extends Logging {

  private val exportDir = Paths.get(configuration.getOptional[String]("variant.export.dir").getOrElse("/tmp/variant-exports"))
  private val exportFileName = "variants-full.jsonl.gz"
  private val metadataFileName = "variants-export-metadata.json"

  // Ensure export directory exists
  if (!Files.exists(exportDir)) {
    Files.createDirectories(exportDir)
  }

  /**
   * Get the path to the current export file.
   */
  def getExportFilePath: Path = exportDir.resolve(exportFileName)

  /**
   * Get the path to the metadata file.
   */
  def getMetadataFilePath: Path = exportDir.resolve(metadataFileName)

  /**
   * Check if an export file exists and return its metadata.
   */
  def getExportMetadata: Option[ExportMetadata] = {
    val metadataPath = getMetadataFilePath
    if (Files.exists(metadataPath)) {
      try {
        val content = Files.readString(metadataPath)
        Json.parse(content).asOpt[ExportMetadata]
      } catch {
        case _: Exception => None
      }
    } else {
      None
    }
  }

  /**
   * Check if export file exists and is recent enough.
   */
  def isExportCurrent(maxAgeHours: Int = 25): Boolean = {
    getExportMetadata.exists { meta =>
      val exportTime = Instant.parse(meta.generatedAt)
      val cutoff = Instant.now().minusSeconds(maxAgeHours * 3600L)
      exportTime.isAfter(cutoff)
    }
  }

  /**
   * Generate a full export of all variants.
   * Returns the number of variants exported.
   */
  def generateExport(): Future[ExportResult] = {
    logger.info("Starting full variant export generation")
    val startTime = System.currentTimeMillis()

    // Write to temp file first, then atomically move
    val tempFile = exportDir.resolve(s"$exportFileName.tmp")

    variantRepository.streamAllGrouped().flatMap { groups =>
      Future {
        var count = 0
        val writer = new OutputStreamWriter(
          new GZIPOutputStream(
            new BufferedOutputStream(
              new FileOutputStream(tempFile.toFile)
            )
          ),
          "UTF-8"
        )

        try {
          groups.foreach { group =>
            val dto = groupToDto(group)
            writer.write(Json.stringify(Json.toJson(dto)))
            writer.write("\n")
            count += 1
            if (count % 100000 == 0) {
              logger.info(s"Exported $count variant groups...")
            }
          }
        } finally {
          writer.close()
        }

        // Atomically move temp file to final location
        Files.move(tempFile, getExportFilePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

        // Write metadata
        val duration = System.currentTimeMillis() - startTime
        val fileSize = Files.size(getExportFilePath)
        val metadata = ExportMetadata(
          generatedAt = Instant.now().toString,
          variantCount = count,
          fileSizeBytes = fileSize,
          generationTimeMs = duration
        )
        Files.writeString(getMetadataFilePath, Json.stringify(Json.toJson(metadata)))

        logger.info(s"Variant export complete: $count groups, ${fileSize / 1024 / 1024}MB, ${duration}ms")

        ExportResult(
          success = true,
          variantCount = count,
          fileSizeBytes = fileSize,
          generationTimeMs = duration
        )
      }
    }.recover { case e: Exception =>
      logger.error(s"Failed to generate variant export: ${e.getMessage}", e)
      // Clean up temp file if it exists
      try { Files.deleteIfExists(tempFile) } catch { case _: Exception => }
      ExportResult(
        success = false,
        variantCount = 0,
        fileSizeBytes = 0,
        generationTimeMs = System.currentTimeMillis() - startTime,
        error = Some(e.getMessage)
      )
    }
  }

  /**
   * Transform a VariantGroup to a PublicVariantDTO (synchronous, no additional DB lookups).
   * For bulk export, we skip per-variant alias/haplogroup lookups for performance.
   */
  private def groupToDto(group: VariantGroup): PublicVariantDTO = {
    val primaryVariant = group.variants.headOption.map(_.variant)
    val primaryVariantId = primaryVariant.flatMap(_.variantId).getOrElse(0)

    // Build coordinates map from all builds
    val coordinates: Map[String, VariantCoordinateDTO] = group.variants.flatMap { vwc =>
      vwc.contig.referenceGenome.map { refGenome =>
        val shortRef = refGenome.split("\\.").head
        shortRef -> VariantCoordinateDTO(
          contig = vwc.contig.commonName.getOrElse(vwc.contig.accession),
          position = vwc.variant.position,
          ref = vwc.variant.referenceAllele,
          alt = vwc.variant.alternateAllele
        )
      }
    }.toMap

    // Determine naming status
    val namingStatus = (group.commonName, group.rsId) match {
      case (Some(_), _) => "NAMED"
      case (None, Some(_)) => "NAMED"
      case (None, None) => "UNNAMED"
    }

    // For bulk export, we use simplified aliases from the primary variant
    val aliasesDto = VariantAliasesDTO(
      commonNames = group.commonName.toSeq,
      rsIds = group.rsId.toSeq,
      sources = Map.empty // Skip detailed source mapping for bulk export
    )

    PublicVariantDTO(
      variantId = primaryVariantId,
      canonicalName = group.commonName.orElse(group.rsId),
      variantType = primaryVariant.map(_.variantType).getOrElse("SNP"),
      namingStatus = namingStatus,
      coordinates = coordinates,
      aliases = aliasesDto,
      definingHaplogroup = None // Skip for bulk export - can be enriched via individual lookups
    )
  }
}

/**
 * Metadata about the generated export file.
 */
case class ExportMetadata(
  generatedAt: String,
  variantCount: Int,
  fileSizeBytes: Long,
  generationTimeMs: Long
)

object ExportMetadata {
  import play.api.libs.json.{Json, OFormat}
  implicit val format: OFormat[ExportMetadata] = Json.format[ExportMetadata]
}

/**
 * Result of an export generation operation.
 */
case class ExportResult(
  success: Boolean,
  variantCount: Int,
  fileSizeBytes: Long,
  generationTimeMs: Long,
  error: Option[String] = None
)

object ExportResult {
  import play.api.libs.json.{Json, OFormat}
  implicit val format: OFormat[ExportResult] = Json.format[ExportResult]
}
