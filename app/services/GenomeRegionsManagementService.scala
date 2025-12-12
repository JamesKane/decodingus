package services

import config.GenomicsConfig
import jakarta.inject.{Inject, Singleton}
import models.api.genomics.*
import models.domain.curator.AuditLogEntry
import models.domain.genomics.{Cytoband, GenbankContig, GenomeRegion, StrMarker}
import play.api.Logging
import play.api.cache.AsyncCacheApi
import play.api.libs.json.{Format, Json}
import repositories.{CuratorAuditRepository, GenomeRegionsRepository}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/**
 * Service for managing genome regions, cytobands, and STR markers.
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
  private given Format[Cytoband] = Json.format[Cytoband]
  private given Format[StrMarker] = Json.format[StrMarker]

  // ============================================================================
  // GenomeRegion Operations
  // ============================================================================

  def listRegions(build: Option[String], page: Int, pageSize: Int): Future[GenomeRegionListResponse] = {
    val offset = (page - 1) * pageSize
    build match {
      case Some(buildName) =>
        val canonicalName = genomicsConfig.resolveReferenceName(buildName)
        for {
          regions <- genomeRegionsRepository.findRegionsByBuild(canonicalName, offset, pageSize)
          total <- genomeRegionsRepository.countRegionsByBuild(Some(canonicalName))
        } yield GenomeRegionListResponse(
          regions = regions.map(toRegionDetailDto),
          total = total,
          page = page,
          pageSize = pageSize
        )
      case None =>
        for {
          total <- genomeRegionsRepository.countRegionsByBuild(None)
        } yield GenomeRegionListResponse(
          regions = Seq.empty,
          total = total,
          page = page,
          pageSize = pageSize
        )
    }
  }

  def getRegion(id: Int): Future[Option[GenomeRegionDetailDto]] = {
    genomeRegionsRepository.findRegionByIdWithContig(id).map(_.map(toRegionDetailDto))
  }

  def createRegion(request: CreateGenomeRegionRequest, userId: Option[UUID]): Future[Either[String, GenomeRegionDetailDto]] = {
    val region = GenomeRegion(
      id = None,
      genbankContigId = request.genbankContigId,
      regionType = request.regionType,
      name = request.name,
      startPos = request.startPos,
      endPos = request.endPos,
      modifier = request.modifier
    )

    genomeRegionsRepository.createRegion(region).flatMap { id =>
      genomeRegionsRepository.findRegionByIdWithContig(id).flatMap {
        case Some((createdRegion, contig)) =>
          logAudit(userId, "genome_region", id, "create", None, Some(createdRegion)).map { _ =>
            invalidateCacheForContig(contig)
            Right(toRegionDetailDto((createdRegion, contig)))
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
    genomeRegionsRepository.findRegionByIdWithContig(id).flatMap {
      case None => Future.successful(Left("Region not found"))
      case Some((oldRegion, contig)) =>
        val updatedRegion = oldRegion.copy(
          regionType = request.regionType.getOrElse(oldRegion.regionType),
          name = request.name.orElse(oldRegion.name),
          startPos = request.startPos.getOrElse(oldRegion.startPos),
          endPos = request.endPos.getOrElse(oldRegion.endPos),
          modifier = request.modifier.orElse(oldRegion.modifier)
        )

        genomeRegionsRepository.updateRegion(id, updatedRegion).flatMap { success =>
          if (success) {
            logAudit(userId, "genome_region", id, "update", Some(oldRegion), Some(updatedRegion)).map { _ =>
              invalidateCacheForContig(contig)
              Right(toRegionDetailDto((updatedRegion.copy(id = Some(id)), contig)))
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
    genomeRegionsRepository.findRegionByIdWithContig(id).flatMap {
      case None => Future.successful(Left("Region not found"))
      case Some((oldRegion, contig)) =>
        genomeRegionsRepository.deleteRegion(id).flatMap { success =>
          if (success) {
            logAudit(userId, "genome_region", id, "delete", Some(oldRegion), None).map { _ =>
              invalidateCacheForContig(contig)
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
  // Cytoband Operations
  // ============================================================================

  def listCytobands(build: Option[String], page: Int, pageSize: Int): Future[CytobandListResponse] = {
    val offset = (page - 1) * pageSize
    build match {
      case Some(buildName) =>
        val canonicalName = genomicsConfig.resolveReferenceName(buildName)
        for {
          cytobands <- genomeRegionsRepository.findCytobandsByBuild(canonicalName, offset, pageSize)
          total <- genomeRegionsRepository.countCytobandsByBuild(Some(canonicalName))
        } yield CytobandListResponse(
          cytobands = cytobands.map(toCytobandDetailDto),
          total = total,
          page = page,
          pageSize = pageSize
        )
      case None =>
        for {
          total <- genomeRegionsRepository.countCytobandsByBuild(None)
        } yield CytobandListResponse(
          cytobands = Seq.empty,
          total = total,
          page = page,
          pageSize = pageSize
        )
    }
  }

  def getCytoband(id: Int): Future[Option[CytobandDetailDto]] = {
    genomeRegionsRepository.findCytobandByIdWithContig(id).map(_.map(toCytobandDetailDto))
  }

  def createCytoband(request: CreateCytobandRequest, userId: Option[UUID]): Future[Either[String, CytobandDetailDto]] = {
    val cytoband = Cytoband(
      id = None,
      genbankContigId = request.genbankContigId,
      name = request.name,
      startPos = request.startPos,
      endPos = request.endPos,
      stain = request.stain
    )

    genomeRegionsRepository.createCytoband(cytoband).flatMap { id =>
      genomeRegionsRepository.findCytobandByIdWithContig(id).flatMap {
        case Some((createdCytoband, contig)) =>
          logAudit(userId, "cytoband", id, "create", None, Some(createdCytoband)).map { _ =>
            invalidateCacheForContig(contig)
            Right(toCytobandDetailDto((createdCytoband, contig)))
          }
        case None =>
          Future.successful(Left("Failed to retrieve created cytoband"))
      }
    }.recover {
      case e: Exception =>
        logger.error(s"Failed to create cytoband: ${e.getMessage}", e)
        Left(s"Failed to create cytoband: ${e.getMessage}")
    }
  }

  def updateCytoband(id: Int, request: UpdateCytobandRequest, userId: Option[UUID]): Future[Either[String, CytobandDetailDto]] = {
    genomeRegionsRepository.findCytobandByIdWithContig(id).flatMap {
      case None => Future.successful(Left("Cytoband not found"))
      case Some((oldCytoband, contig)) =>
        val updatedCytoband = oldCytoband.copy(
          name = request.name.getOrElse(oldCytoband.name),
          startPos = request.startPos.getOrElse(oldCytoband.startPos),
          endPos = request.endPos.getOrElse(oldCytoband.endPos),
          stain = request.stain.getOrElse(oldCytoband.stain)
        )

        genomeRegionsRepository.updateCytoband(id, updatedCytoband).flatMap { success =>
          if (success) {
            logAudit(userId, "cytoband", id, "update", Some(oldCytoband), Some(updatedCytoband)).map { _ =>
              invalidateCacheForContig(contig)
              Right(toCytobandDetailDto((updatedCytoband.copy(id = Some(id)), contig)))
            }
          } else {
            Future.successful(Left("Failed to update cytoband"))
          }
        }
    }.recover {
      case e: Exception =>
        logger.error(s"Failed to update cytoband: ${e.getMessage}", e)
        Left(s"Failed to update cytoband: ${e.getMessage}")
    }
  }

  def deleteCytoband(id: Int, userId: Option[UUID]): Future[Either[String, Unit]] = {
    genomeRegionsRepository.findCytobandByIdWithContig(id).flatMap {
      case None => Future.successful(Left("Cytoband not found"))
      case Some((oldCytoband, contig)) =>
        genomeRegionsRepository.deleteCytoband(id).flatMap { success =>
          if (success) {
            logAudit(userId, "cytoband", id, "delete", Some(oldCytoband), None).map { _ =>
              invalidateCacheForContig(contig)
              Right(())
            }
          } else {
            Future.successful(Left("Failed to delete cytoband"))
          }
        }
    }.recover {
      case e: Exception =>
        logger.error(s"Failed to delete cytoband: ${e.getMessage}", e)
        Left(s"Failed to delete cytoband: ${e.getMessage}")
    }
  }

  def bulkCreateCytobands(request: BulkCreateCytobandsRequest, userId: Option[UUID]): Future[BulkOperationResponse] = {
    val results = request.cytobands.zipWithIndex.map { case (req, idx) =>
      createCytoband(req, userId).map {
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
  // StrMarker Operations
  // ============================================================================

  def listStrMarkers(build: Option[String], page: Int, pageSize: Int): Future[StrMarkerListResponse] = {
    val offset = (page - 1) * pageSize
    build match {
      case Some(buildName) =>
        val canonicalName = genomicsConfig.resolveReferenceName(buildName)
        for {
          markers <- genomeRegionsRepository.findStrMarkersByBuild(canonicalName, offset, pageSize)
          total <- genomeRegionsRepository.countStrMarkersByBuild(Some(canonicalName))
        } yield StrMarkerListResponse(
          markers = markers.map(toStrMarkerDetailDto),
          total = total,
          page = page,
          pageSize = pageSize
        )
      case None =>
        for {
          total <- genomeRegionsRepository.countStrMarkersByBuild(None)
        } yield StrMarkerListResponse(
          markers = Seq.empty,
          total = total,
          page = page,
          pageSize = pageSize
        )
    }
  }

  def getStrMarker(id: Int): Future[Option[StrMarkerDetailDto]] = {
    genomeRegionsRepository.findStrMarkerByIdWithContig(id).map(_.map(toStrMarkerDetailDto))
  }

  def createStrMarker(request: CreateStrMarkerRequest, userId: Option[UUID]): Future[Either[String, StrMarkerDetailDto]] = {
    val marker = StrMarker(
      id = None,
      genbankContigId = request.genbankContigId,
      name = request.name,
      startPos = request.startPos,
      endPos = request.endPos,
      period = request.period,
      verified = request.verified,
      note = request.note
    )

    genomeRegionsRepository.createStrMarker(marker).flatMap { id =>
      genomeRegionsRepository.findStrMarkerByIdWithContig(id).flatMap {
        case Some((createdMarker, contig)) =>
          logAudit(userId, "str_marker", id, "create", None, Some(createdMarker)).map { _ =>
            invalidateCacheForContig(contig)
            Right(toStrMarkerDetailDto((createdMarker, contig)))
          }
        case None =>
          Future.successful(Left("Failed to retrieve created STR marker"))
      }
    }.recover {
      case e: Exception =>
        logger.error(s"Failed to create STR marker: ${e.getMessage}", e)
        Left(s"Failed to create STR marker: ${e.getMessage}")
    }
  }

  def updateStrMarker(id: Int, request: UpdateStrMarkerRequest, userId: Option[UUID]): Future[Either[String, StrMarkerDetailDto]] = {
    genomeRegionsRepository.findStrMarkerByIdWithContig(id).flatMap {
      case None => Future.successful(Left("STR marker not found"))
      case Some((oldMarker, contig)) =>
        val updatedMarker = oldMarker.copy(
          name = request.name.getOrElse(oldMarker.name),
          startPos = request.startPos.getOrElse(oldMarker.startPos),
          endPos = request.endPos.getOrElse(oldMarker.endPos),
          period = request.period.getOrElse(oldMarker.period),
          verified = request.verified.getOrElse(oldMarker.verified),
          note = request.note.orElse(oldMarker.note)
        )

        genomeRegionsRepository.updateStrMarker(id, updatedMarker).flatMap { success =>
          if (success) {
            logAudit(userId, "str_marker", id, "update", Some(oldMarker), Some(updatedMarker)).map { _ =>
              invalidateCacheForContig(contig)
              Right(toStrMarkerDetailDto((updatedMarker.copy(id = Some(id)), contig)))
            }
          } else {
            Future.successful(Left("Failed to update STR marker"))
          }
        }
    }.recover {
      case e: Exception =>
        logger.error(s"Failed to update STR marker: ${e.getMessage}", e)
        Left(s"Failed to update STR marker: ${e.getMessage}")
    }
  }

  def deleteStrMarker(id: Int, userId: Option[UUID]): Future[Either[String, Unit]] = {
    genomeRegionsRepository.findStrMarkerByIdWithContig(id).flatMap {
      case None => Future.successful(Left("STR marker not found"))
      case Some((oldMarker, contig)) =>
        genomeRegionsRepository.deleteStrMarker(id).flatMap { success =>
          if (success) {
            logAudit(userId, "str_marker", id, "delete", Some(oldMarker), None).map { _ =>
              invalidateCacheForContig(contig)
              Right(())
            }
          } else {
            Future.successful(Left("Failed to delete STR marker"))
          }
        }
    }.recover {
      case e: Exception =>
        logger.error(s"Failed to delete STR marker: ${e.getMessage}", e)
        Left(s"Failed to delete STR marker: ${e.getMessage}")
    }
  }

  def bulkCreateStrMarkers(request: BulkCreateStrMarkersRequest, userId: Option[UUID]): Future[BulkOperationResponse] = {
    val results = request.markers.zipWithIndex.map { case (req, idx) =>
      createStrMarker(req, userId).map {
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

  private def toRegionDetailDto(data: (GenomeRegion, GenbankContig)): GenomeRegionDetailDto = {
    val (region, contig) = data
    GenomeRegionDetailDto(
      id = region.id.getOrElse(0),
      genbankContigId = region.genbankContigId,
      contigName = contig.commonName,
      referenceGenome = contig.referenceGenome,
      regionType = region.regionType,
      name = region.name,
      startPos = region.startPos,
      endPos = region.endPos,
      modifier = region.modifier
    )
  }

  private def toCytobandDetailDto(data: (Cytoband, GenbankContig)): CytobandDetailDto = {
    val (cytoband, contig) = data
    CytobandDetailDto(
      id = cytoband.id.getOrElse(0),
      genbankContigId = cytoband.genbankContigId,
      contigName = contig.commonName,
      referenceGenome = contig.referenceGenome,
      name = cytoband.name,
      startPos = cytoband.startPos,
      endPos = cytoband.endPos,
      stain = cytoband.stain
    )
  }

  private def toStrMarkerDetailDto(data: (StrMarker, GenbankContig)): StrMarkerDetailDto = {
    val (marker, contig) = data
    StrMarkerDetailDto(
      id = marker.id.getOrElse(0),
      genbankContigId = marker.genbankContigId,
      contigName = contig.commonName,
      referenceGenome = contig.referenceGenome,
      name = marker.name,
      startPos = marker.startPos,
      endPos = marker.endPos,
      period = marker.period,
      verified = marker.verified,
      note = marker.note
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

  private def invalidateCacheForContig(contig: GenbankContig): Unit = {
    contig.referenceGenome.foreach { refGenome =>
      cache.remove(s"genome-regions:$refGenome")
      logger.debug(s"Invalidated cache for genome-regions:$refGenome")
    }
  }
}
