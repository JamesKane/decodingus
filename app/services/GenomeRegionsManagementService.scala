package services

import config.GenomicsConfig
import jakarta.inject.{Inject, Singleton}
import models.api.genomics.*
import models.domain.curator.AuditLogEntry
import models.domain.genomics.{GenomeRegion, RegionCoordinate}
import play.api.Logging
import play.api.cache.AsyncCacheApi
import play.api.libs.json.{Format, JsValue, Json}
import repositories.{CuratorAuditRepository, GenomeRegionsRepository}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/**
 * Service for managing genome regions (including cytobands).
 * Provides CRUD operations with audit logging.
 */
@Singleton
class GenomeRegionsManagementService @Inject()(
  genomeRegionsRepository: GenomeRegionsRepository,
  auditRepository: CuratorAuditRepository,
  genomicsConfig: GenomicsConfig,
  cache: AsyncCacheApi
)(implicit ec: ExecutionContext) extends Logging {

  // System UUID for API-originated changes
  private val SystemUserId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")

  // JSON formats for domain objects
  private given Format[GenomeRegion] = Json.format[GenomeRegion]

  // ============================================================================
  // GenomeRegion Operations
  // ============================================================================

  def listRegions(regionType: Option[String], build: Option[String], page: Int, pageSize: Int): Future[GenomeRegionListResponse] = {
    val offset = (page - 1) * pageSize
    val canonicalBuild = build.map(genomicsConfig.resolveReferenceName)
    
    for {
      regions <- genomeRegionsRepository.findRegions(regionType, canonicalBuild, offset, pageSize)
      total <- genomeRegionsRepository.countRegions(regionType, canonicalBuild)
    } yield GenomeRegionListResponse(
      regions = regions.map(toRegionDetailDto),
      total = total,
      page = page,
      pageSize = pageSize
    )
  }

  def getRegion(id: Int): Future[Option[GenomeRegionDetailDto]] = {
    genomeRegionsRepository.findRegionById(id).map(_.map(toRegionDetailDto))
  }

  def createRegion(request: CreateGenomeRegionRequest, userId: Option[UUID]): Future[Either[String, GenomeRegionDetailDto]] = {
    val region = GenomeRegion(
      id = None,
      regionType = request.regionType,
      name = request.name,
      coordinates = request.coordinates.map { case (k, v) => k -> RegionCoordinate(v.contig, v.start, v.end) },
      properties = request.properties.getOrElse(Json.obj())
    )

    genomeRegionsRepository.createRegion(region).flatMap { id =>
      genomeRegionsRepository.findRegionById(id).flatMap {
        case Some(createdRegion) =>
          logAudit(userId, "genome_region", id, "create", None, Some(createdRegion)).map { _ =>
            invalidateCache()
            Right(toRegionDetailDto(createdRegion))
          }
        case None =>
          Future.successful(Left("Failed to retrieve created region"))
      }
    }.recover {
      case e: Exception =>
        logger.error(s"Failed to create genome region: ${e.getMessage}", e)
        Left(s"Failed to create genome region: ${e.getMessage}")
    }
  }

  def updateRegion(id: Int, request: UpdateGenomeRegionRequest, userId: Option[UUID]): Future[Either[String, GenomeRegionDetailDto]] = {
    genomeRegionsRepository.findRegionById(id).flatMap {
      case None => Future.successful(Left("Region not found"))
      case Some(oldRegion) =>
        val updatedRegion = oldRegion.copy(
          regionType = request.regionType.getOrElse(oldRegion.regionType),
          name = request.name.orElse(oldRegion.name),
          coordinates = request.coordinates.map(_.map { case (k, v) => k -> RegionCoordinate(v.contig, v.start, v.end) }).getOrElse(oldRegion.coordinates),
          properties = request.properties.getOrElse(oldRegion.properties)
        )

        genomeRegionsRepository.updateRegion(id, updatedRegion).flatMap { success =>
          if (success) {
            logAudit(userId, "genome_region", id, "update", Some(oldRegion), Some(updatedRegion)).map { _ =>
              invalidateCache()
              Right(toRegionDetailDto(updatedRegion.copy(id = Some(id))))
            }
          } else {
            Future.successful(Left("Failed to update region"))
          }
        }
    }.recover {
      case e: Exception =>
        logger.error(s"Failed to update genome region: ${e.getMessage}", e)
        Left(s"Failed to update genome region: ${e.getMessage}")
    }
  }

  def deleteRegion(id: Int, userId: Option[UUID]): Future[Either[String, Unit]] = {
    genomeRegionsRepository.findRegionById(id).flatMap {
      case None => Future.successful(Left("Region not found"))
      case Some(oldRegion) =>
        genomeRegionsRepository.deleteRegion(id).flatMap { success =>
          if (success) {
            logAudit(userId, "genome_region", id, "delete", Some(oldRegion), None).map { _ =>
              invalidateCache()
              Right(())
            }
          } else {
            Future.successful(Left("Failed to delete region"))
          }
        }
    }.recover {
      case e: Exception =>
        logger.error(s"Failed to delete genome region: ${e.getMessage}", e)
        Left(s"Failed to delete genome region: ${e.getMessage}")
    }
  }

  def bulkCreateRegions(request: BulkCreateGenomeRegionsRequest, userId: Option[UUID]): Future[BulkOperationResponse] = {
    val results = request.regions.zipWithIndex.map { case (req, idx) =>
      createRegion(req, userId).map {
        case Right(dto) => BulkOperationResult(idx, "success", Some(dto.id), None)
        case Left(error) => BulkOperationResult(idx, "error", None, Some(error))
      }.recover {
        case e: Exception => BulkOperationResult(idx, "error", None, Some(e.getMessage))
      }
    }

    Future.sequence(results).map { resultList =>
      BulkOperationResponse(
        total = resultList.size,
        succeeded = resultList.count(_.status == "success"),
        failed = resultList.count(_.status == "error"),
        results = resultList
      )
    }
  }

  // ============================================================================
  // Helper Methods
  // ============================================================================

  private def toRegionDetailDto(region: GenomeRegion): GenomeRegionDetailDto = {
    GenomeRegionDetailDto(
      id = region.id.getOrElse(0),
      regionType = region.regionType,
      name = region.name,
      coordinates = region.coordinates.map { case (k, v) => k -> RegionCoordinateDto(v.contig, v.start, v.end) },
      properties = region.properties
    )
  }

  private def logAudit[T](userId: Option[UUID], entityType: String, entityId: Int, action: String,
                          oldValue: Option[T], newValue: Option[T])(using Format[T]): Future[AuditLogEntry] = {
    val effectiveUserId = userId.getOrElse(SystemUserId)
    val entry = AuditLogEntry(
      userId = effectiveUserId,
      entityType = entityType,
      entityId = entityId,
      action = action,
      oldValue = oldValue.map(Json.toJson(_)),
      newValue = newValue.map(Json.toJson(_)),
      comment = if (userId.isEmpty) Some("API system change") else None
    )
    auditRepository.logAction(entry)
  }

  private def invalidateCache(): Unit = {
    // Invalidate all build caches as we don't know easily which builds are affected by coordinates update
    genomicsConfig.supportedReferences.foreach { refGenome =>
      cache.remove(s"genome-regions:$refGenome")
      logger.debug(s"Invalidated cache for genome-regions:$refGenome")
    }
  }
}