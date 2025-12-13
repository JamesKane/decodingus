package services

import jakarta.inject.{Inject, Singleton}
import models.api.*
import models.domain.genomics.VariantV2
import play.api.{Configuration, Logging}
import play.api.libs.json.{JsObject, Json, OFormat}
import repositories.{HaplogroupVariantRepository, VariantV2Repository}

import java.io.{BufferedOutputStream, FileOutputStream, OutputStreamWriter}
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.util.zip.GZIPOutputStream
import scala.concurrent.{ExecutionContext, Future}

/**
 * Export metadata for tracking export file status.
 */
case class ExportMetadata(
  generatedAt: Instant,
  variantCount: Int,
  fileSizeBytes: Long
)

object ExportMetadata {
  implicit val format: OFormat[ExportMetadata] = Json.format[ExportMetadata]
}

/**
 * Result of an export operation.
 */
case class ExportResult(
  success: Boolean,
  variantCount: Int = 0,
  fileSizeBytes: Long = 0,
  error: Option[String] = None,
  generationTimeMs: Long = 0
)

object ExportResult {
  implicit val format: OFormat[ExportResult] = Json.format[ExportResult]
}

/**
 * Record structure for exported variants.
 */
case class VariantExportRecord(
  variantId: Int,
  canonicalName: Option[String],
  variantType: String,
  namingStatus: String,
  coordinates: Map[String, VariantCoordinateDTO],
  rsIds: Seq[String],
  commonNames: Seq[String]
)

object VariantExportRecord {
  implicit val format: OFormat[VariantExportRecord] = Json.format[VariantExportRecord]
}

/**
 * Coordinate information for export.
 */
case class VariantCoordinateDTO(
  contig: String,
  position: Int,
  ref: String,
  alt: String
)

object VariantCoordinateDTO {
  implicit val format: OFormat[VariantCoordinateDTO] = Json.format[VariantCoordinateDTO]
}

/**
 * Service for generating bulk variant export files.
 * Creates a gzipped JSONL file containing all variants for Edge App consumption.
 */
@Singleton
class VariantExportService @Inject()(
  variantV2Repository: VariantV2Repository,
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
    val metaPath = getMetadataFilePath
    if (Files.exists(metaPath)) {
      try {
        val content = Files.readString(metaPath)
        Some(Json.parse(content).as[ExportMetadata])
      } catch {
        case e: Exception =>
          logger.warn(s"Failed to read export metadata: ${e.getMessage}")
          None
      }
    } else {
      None
    }
  }

  /**
   * Generate a new export file.
   */
  def generateExport(): Future[ExportResult] = {
    val startTime = System.currentTimeMillis()
    logger.info("Starting variant export generation")

    variantV2Repository.streamAll().map { variants =>
      try {
        val tempFile = exportDir.resolve(s"$exportFileName.tmp")
        val finalFile = getExportFilePath

        // Write variants to gzipped JSONL
        val gzOut = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(tempFile.toFile)))
        val writer = new OutputStreamWriter(gzOut, "UTF-8")

        try {
          for (variant <- variants) {
            val exportRecord = variantToExportRecord(variant)
            writer.write(Json.stringify(Json.toJson(exportRecord)))
            writer.write("\n")
          }
        } finally {
          writer.close()
        }

        // Atomically move temp file to final location
        Files.move(tempFile, finalFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

        val fileSizeBytes = Files.size(finalFile)

        // Generate and save metadata
        val metadata = ExportMetadata(
          generatedAt = Instant.now(),
          variantCount = variants.size,
          fileSizeBytes = fileSizeBytes
        )

        Files.writeString(getMetadataFilePath, Json.stringify(Json.toJson(metadata)))

        val generationTimeMs = System.currentTimeMillis() - startTime
        logger.info(s"Export generation complete: ${variants.size} variants in ${generationTimeMs}ms")

        ExportResult(
          success = true,
          variantCount = variants.size,
          fileSizeBytes = fileSizeBytes,
          error = None,
          generationTimeMs = generationTimeMs
        )
      } catch {
        case e: Exception =>
          logger.error(s"Export generation failed: ${e.getMessage}", e)
          ExportResult(
            success = false,
            variantCount = 0,
            fileSizeBytes = 0,
            error = Some(e.getMessage),
            generationTimeMs = System.currentTimeMillis() - startTime
          )
      }
    }
  }

  /**
   * Convert a VariantV2 to an export record.
   */
  private def variantToExportRecord(variant: VariantV2): VariantExportRecord = {
    // Extract coordinates from JSONB
    val coordinates = variant.coordinates.asOpt[Map[String, JsObject]].getOrElse(Map.empty)

    val coordDtos = coordinates.flatMap { case (refGenome, coords) =>
      for {
        contig <- (coords \ "contig").asOpt[String]
        position <- (coords \ "position").asOpt[Int]
        ref <- (coords \ "ref").asOpt[String]
        alt <- (coords \ "alt").asOpt[String]
      } yield refGenome -> VariantCoordinateDTO(
        contig = contig,
        position = position,
        ref = ref,
        alt = alt
      )
    }

    // Extract aliases from JSONB
    val rsIds = variant.rsIds
    val commonNames = variant.commonNames

    VariantExportRecord(
      variantId = variant.variantId.getOrElse(0),
      canonicalName = variant.canonicalName,
      variantType = variant.mutationType.dbValue,
      namingStatus = variant.namingStatus.dbValue,
      coordinates = coordDtos,
      rsIds = rsIds,
      commonNames = commonNames
    )
  }
}
