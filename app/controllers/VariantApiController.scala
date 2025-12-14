package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Singleton}
import models.api.*
import models.domain.genomics.VariantV2
import play.api.Logger
import play.api.libs.json.{JsObject, Json}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import repositories.VariantV2Repository

import scala.concurrent.{ExecutionContext, Future}

/**
 * Private API controller for bulk variant operations.
 * Secured with X-API-Key authentication.
 *
 * Updated for VariantV2 schema with JSONB coordinates and aliases.
 */
@Singleton
class VariantApiController @Inject()(
    val controllerComponents: ControllerComponents,
    secureApi: ApiSecurityAction,
    variantRepository: VariantV2Repository
)(implicit ec: ExecutionContext) extends BaseController {

  private val logger = Logger(this.getClass)

  /**
   * Bulk add reference builds (coordinates) to existing variants.
   * Matches variants by name or rsId, then adds coordinates for the specified reference genome.
   */
  def bulkAddBuilds(): Action[BulkAddVariantBuildsRequest] =
    secureApi.jsonAction[BulkAddVariantBuildsRequest].async { request =>
      val requests = request.body.variants
      logger.info(s"Bulk add builds request for ${requests.size} variants")

      val resultFutures = requests.map(processAddBuildRequest)

      Future.sequence(resultFutures).map { results =>
        val succeeded = results.count(_.status == "success")
        val failed = results.count(_.status != "success")

        logger.info(s"Bulk add builds completed: $succeeded succeeded, $failed failed")

        Ok(Json.toJson(BulkVariantOperationResponse(
          total = results.size,
          succeeded = succeeded,
          failed = failed,
          results = results
        )))
      }
    }

  /**
   * Bulk update rsIds for variants matched by name.
   * Adds rsId as an alias to the variant's aliases JSONB.
   */
  def bulkUpdateRsIds(): Action[BulkUpdateRsIdsRequest] =
    secureApi.jsonAction[BulkUpdateRsIdsRequest].async { request =>
      val requests = request.body.variants
      logger.info(s"Bulk update rsIds request for ${requests.size} variants")

      val resultFutures = requests.map(processUpdateRsIdRequest)

      Future.sequence(resultFutures).map { results =>
        val succeeded = results.count(_.status == "success")
        val failed = results.count(_.status != "success")

        logger.info(s"Bulk update rsIds completed: $succeeded succeeded, $failed failed")

        Ok(Json.toJson(BulkVariantOperationResponse(
          total = results.size,
          succeeded = succeeded,
          failed = failed,
          results = results
        )))
      }
    }

  private def processAddBuildRequest(req: AddVariantBuildRequest): Future[VariantOperationResult] = {
    val identifier = req.name.orElse(req.rsId)

    identifier match {
      case None =>
        Future.successful(VariantOperationResult(
          name = req.name,
          rsId = req.rsId,
          status = "error",
          message = Some("Either name or rsId must be provided")
        ))

      case Some(id) =>
        // Find variant by name or alias
        val findFuture = req.name match {
          case Some(name) => variantRepository.findByCanonicalName(name)
          case None => variantRepository.findByAlias(req.rsId.get).map(_.headOption)
        }

        findFuture.flatMap {
          case None =>
            Future.successful(VariantOperationResult(
              name = req.name,
              rsId = req.rsId,
              status = "error",
              message = Some(s"Variant not found with identifier '$id'")
            ))

          case Some(variant) =>
            // Check if this build already exists
            val existingCoords = variant.coordinates.asOpt[Map[String, JsObject]].getOrElse(Map.empty)
            if (existingCoords.contains(req.refGenome)) {
              Future.successful(VariantOperationResult(
                name = req.name,
                rsId = req.rsId,
                status = "skipped",
                message = Some(s"Build ${req.refGenome} already exists"),
                variantId = variant.variantId
              ))
            } else {
              // Add the new coordinates
              val newCoords = Json.obj(
                "contig" -> req.contig,
                "position" -> req.position,
                "ref" -> req.refAllele,
                "alt" -> req.altAllele
              )

              variantRepository.addCoordinates(variant.variantId.get, req.refGenome, newCoords).map { _ =>
                VariantOperationResult(
                  name = req.name,
                  rsId = req.rsId,
                  status = "success",
                  message = Some(s"Added coordinates for ${req.refGenome}"),
                  variantId = variant.variantId
                )
              }.recover { case e: Exception =>
                logger.error(s"Failed to add coordinates: ${e.getMessage}", e)
                VariantOperationResult(
                  name = req.name,
                  rsId = req.rsId,
                  status = "error",
                  message = Some(s"Database error: ${e.getMessage}")
                )
              }
            }
        }
    }
  }

  private def processUpdateRsIdRequest(req: UpdateVariantRsIdRequest): Future[VariantOperationResult] = {
    variantRepository.findByCanonicalName(req.name).flatMap {
      case None =>
        // Try finding by alias
        variantRepository.findByAlias(req.name).flatMap { variants =>
          if (variants.isEmpty) {
            Future.successful(VariantOperationResult(
              name = Some(req.name),
              rsId = Some(req.rsId),
              status = "error",
              message = Some(s"No variant found with name '${req.name}'")
            ))
          } else {
            updateVariantRsId(variants.head, req)
          }
        }

      case Some(variant) =>
        updateVariantRsId(variant, req)
    }
  }

  private def updateVariantRsId(variant: VariantV2, req: UpdateVariantRsIdRequest): Future[VariantOperationResult] = {
    // Add rsId as an alias
    variantRepository.addAlias(variant.variantId.get, "rs_id", req.rsId, Some("bulk_update")).map { _ =>
      VariantOperationResult(
        name = Some(req.name),
        rsId = Some(req.rsId),
        status = "success",
        message = Some("Added rsId as alias"),
        variantId = variant.variantId
      )
    }.recover { case e: Exception =>
      logger.error(s"Failed to update rsId: ${e.getMessage}", e)
      VariantOperationResult(
        name = Some(req.name),
        rsId = Some(req.rsId),
        status = "error",
        message = Some(s"Database error: ${e.getMessage}")
      )
    }
  }

  // ============================================================================
  // Alias Source Management Endpoints
  // ============================================================================

  /**
   * Bulk update alias sources by prefix pattern.
   * Updates the source field in aliases JSONB for matching alias values.
   */
  def bulkUpdateAliasSources(): Action[BulkUpdateAliasSourcesRequest] =
    secureApi.jsonAction[BulkUpdateAliasSourcesRequest].async { request =>
      val updates = request.body.updates
      logger.info(s"Bulk update alias sources request for ${updates.size} prefix patterns")

      val resultFutures = updates.map { req =>
        variantRepository.bulkUpdateAliasSource(req.aliasPrefix, req.newSource, req.oldSource).map { count =>
          AliasSourceUpdateResult(
            aliasPrefix = req.aliasPrefix,
            newSource = req.newSource,
            aliasesUpdated = count,
            status = "success",
            message = Some(s"Updated $count aliases")
          )
        }.recover { case e: Exception =>
          logger.error(s"Failed to update aliases for prefix '${req.aliasPrefix}': ${e.getMessage}", e)
          AliasSourceUpdateResult(
            aliasPrefix = req.aliasPrefix,
            newSource = req.newSource,
            aliasesUpdated = 0,
            status = "error",
            message = Some(s"Database error: ${e.getMessage}")
          )
        }
      }

      Future.sequence(resultFutures).map { results =>
        val totalUpdated = results.map(_.aliasesUpdated).sum
        logger.info(s"Bulk update alias sources completed: $totalUpdated total aliases updated")

        Ok(Json.toJson(BulkAliasSourceUpdateResponse(
          total = results.size,
          totalAliasesUpdated = totalUpdated,
          results = results
        )))
      }
    }

  /**
   * Get statistics about alias sources in the database.
   */
  def getAliasSourceStats(): Action[AnyContent] = secureApi.async { _ =>
    variantRepository.getAliasSourceStats().map { stats =>
      val totalAliases = stats.map(_._2).sum
      Ok(Json.toJson(AliasSourceStatsResponse(
        sources = stats.map { case (source, count) => AliasSourceSummary(source, count) },
        totalAliases = totalAliases
      )))
    }
  }

  /**
   * Preview how many aliases would be affected by a source update.
   */
  def previewAliasSourceUpdate(aliasPrefix: String, currentSource: String): Action[AnyContent] = secureApi.async { _ =>
    variantRepository.countAliasesByPrefixAndSource(aliasPrefix, Some(currentSource)).map { count =>
      Ok(Json.obj(
        "aliasPrefix" -> aliasPrefix,
        "currentSource" -> currentSource,
        "matchingAliases" -> count
      ))
    }
  }

  // ============================================================================
  // DU Naming Authority Endpoints
  // ============================================================================

  /**
   * Assign a DU name to a single variant.
   * The variant must exist and not already have a DU name.
   */
  def assignDuName(variantId: Int): Action[AnyContent] = secureApi.async { _ =>
    variantRepository.findById(variantId).flatMap {
      case None =>
        Future.successful(NotFound(Json.toJson(DuNameAssignmentResult(
          variantId = variantId,
          duName = None,
          previousName = None,
          status = "error",
          message = Some(s"Variant $variantId not found")
        ))))

      case Some(variant) =>
        // Check if already has a DU name
        if (variant.canonicalName.exists(variantRepository.isDuName)) {
          Future.successful(Ok(Json.toJson(DuNameAssignmentResult(
            variantId = variantId,
            duName = variant.canonicalName,
            previousName = variant.canonicalName,
            status = "skipped",
            message = Some("Variant already has a DU name")
          ))))
        } else {
          // Assign new DU name
          assignDuNameToVariant(variant).map { result =>
            Ok(Json.toJson(result))
          }
        }
    }
  }

  /**
   * Bulk assign DU names to multiple variants.
   * Skips variants that already have DU names.
   */
  def bulkAssignDuNames(): Action[BulkAssignDuNamesRequest] =
    secureApi.jsonAction[BulkAssignDuNamesRequest].async { request =>
      val variantIds = request.body.variantIds
      logger.info(s"Bulk assign DU names request for ${variantIds.size} variants")

      // Process sequentially to maintain name ordering
      variantIds.foldLeft(Future.successful(Seq.empty[DuNameAssignmentResult])) { (accFuture, variantId) =>
        accFuture.flatMap { acc =>
          processAssignDuName(variantId).map(result => acc :+ result)
        }
      }.map { results =>
        val succeeded = results.count(_.status == "success")
        val failed = results.count(_.status == "error")
        val skipped = results.count(_.status == "skipped")

        logger.info(s"Bulk assign DU names completed: $succeeded succeeded, $failed failed, $skipped skipped")

        Ok(Json.toJson(BulkDuNameAssignmentResponse(
          total = results.size,
          succeeded = succeeded,
          failed = failed,
          skipped = skipped,
          results = results
        )))
      }
    }

  /**
   * Get the next DU name that would be assigned (preview without consuming).
   */
  def previewNextDuName(): Action[AnyContent] = secureApi.async { _ =>
    variantRepository.nextDuName().map { nextName =>
      Ok(Json.obj(
        "nextDuName" -> nextName,
        "note" -> "This name has been reserved. Use assignDuName to apply it to a variant."
      ))
    }
  }

  private def processAssignDuName(variantId: Int): Future[DuNameAssignmentResult] = {
    variantRepository.findById(variantId).flatMap {
      case None =>
        Future.successful(DuNameAssignmentResult(
          variantId = variantId,
          duName = None,
          previousName = None,
          status = "error",
          message = Some(s"Variant $variantId not found")
        ))

      case Some(variant) =>
        if (variant.canonicalName.exists(variantRepository.isDuName)) {
          Future.successful(DuNameAssignmentResult(
            variantId = variantId,
            duName = variant.canonicalName,
            previousName = variant.canonicalName,
            status = "skipped",
            message = Some("Variant already has a DU name")
          ))
        } else {
          assignDuNameToVariant(variant)
        }
    }
  }

  private def assignDuNameToVariant(variant: VariantV2): Future[DuNameAssignmentResult] = {
    val previousName = variant.canonicalName

    for {
      duName <- variantRepository.nextDuName()
      updated = variant.copy(
        canonicalName = Some(duName),
        namingStatus = models.domain.genomics.NamingStatus.Named
      )
      success <- variantRepository.update(updated)
    } yield {
      if (success) {
        logger.info(s"Assigned DU name $duName to variant ${variant.variantId.get} (was: ${previousName.getOrElse("unnamed")})")
        DuNameAssignmentResult(
          variantId = variant.variantId.get,
          duName = Some(duName),
          previousName = previousName,
          status = "success",
          message = Some(s"Assigned $duName")
        )
      } else {
        DuNameAssignmentResult(
          variantId = variant.variantId.get,
          duName = None,
          previousName = previousName,
          status = "error",
          message = Some("Failed to update variant")
        )
      }
    }
  }
}
