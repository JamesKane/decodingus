package controllers

import actions.ApiSecurityAction
import jakarta.inject.{Inject, Singleton}
import models.api.*
import models.dal.domain.genomics.Variant
import play.api.Logger
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import repositories.{GenbankContigRepository, VariantAliasRepository, VariantRepository}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Private API controller for bulk variant operations.
 * Secured with X-API-Key authentication.
 *
 * Endpoints are unlisted and intended for system integration.
 */
@Singleton
class VariantApiController @Inject()(
                                      val controllerComponents: ControllerComponents,
                                      secureApi: ApiSecurityAction,
                                      variantRepository: VariantRepository,
                                      variantAliasRepository: VariantAliasRepository,
                                      genbankContigRepository: GenbankContigRepository
                                    )(implicit ec: ExecutionContext) extends BaseController {

  private val logger = Logger(this.getClass)

  /**
   * Bulk add reference builds to existing variants.
   * Matches variants by name or rsId, then creates new variant records
   * for the specified reference genome if they don't exist.
   */
  def bulkAddBuilds(): Action[BulkAddVariantBuildsRequest] =
    secureApi.jsonAction[BulkAddVariantBuildsRequest].async { request =>
      val requests = request.body.variants
      logger.info(s"Bulk add builds request for ${requests.size} variants")

      // Collect all unique contig+genome combinations needed
      val contigGenomePairs = requests.map(r => (r.contig, r.refGenome)).distinct

      // Resolve all contigs
      genbankContigRepository.findByCommonNames(contigGenomePairs.map(_._1).distinct).flatMap { contigs =>
        // Build lookup map: (commonName, refGenome) -> contigId
        val contigMap = contigs.flatMap { c =>
          for {
            cn <- c.commonName
            rg <- c.referenceGenome
          } yield (cn, rg) -> c.id.get
        }.toMap

        // Also try without 'chr' prefix
        val contigMapWithFallback = contigMap ++ contigs.flatMap { c =>
          for {
            cn <- c.commonName
            rg <- c.referenceGenome
          } yield (cn.stripPrefix("chr"), rg) -> c.id.get
        }.toMap

        // Process each request
        val resultFutures = requests.map { req =>
          processAddBuildRequest(req, contigMapWithFallback)
        }

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
    }

  /**
   * Bulk update rsIds for variants matched by name.
   */
  def bulkUpdateRsIds(): Action[BulkUpdateRsIdsRequest] =
    secureApi.jsonAction[BulkUpdateRsIdsRequest].async { request =>
      val requests = request.body.variants
      logger.info(s"Bulk update rsIds request for ${requests.size} variants")

      val resultFutures = requests.map { req =>
        processUpdateRsIdRequest(req)
      }

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

  private def processAddBuildRequest(
    req: AddVariantBuildRequest,
    contigMap: Map[(String, String), Int]
  ): Future[VariantOperationResult] = {
    val identifier = req.name.orElse(req.rsId)

    // Resolve contig ID
    val contigIdOpt = contigMap.get((req.contig, req.refGenome))
      .orElse(contigMap.get((req.contig.stripPrefix("chr"), req.refGenome)))
      .orElse(contigMap.get(("chr" + req.contig.stripPrefix("chr"), req.refGenome)))

    contigIdOpt match {
      case None =>
        Future.successful(VariantOperationResult(
          name = req.name,
          rsId = req.rsId,
          status = "error",
          message = Some(s"Contig '${req.contig}' not found for reference genome '${req.refGenome}'")
        ))

      case Some(contigId) =>
        // Check if this exact build already exists
        variantRepository.findVariant(contigId, req.position, req.refAllele, req.altAllele).flatMap {
          case Some(existing) =>
            // Build already exists
            Future.successful(VariantOperationResult(
              name = req.name,
              rsId = req.rsId,
              status = "skipped",
              message = Some("Build already exists"),
              variantId = existing.variantId
            ))

          case None =>
            // Create new variant record for this build
            val newVariant = Variant(
              genbankContigId = contigId,
              position = req.position,
              referenceAllele = req.refAllele,
              alternateAllele = req.altAllele,
              variantType = req.variantType,
              rsId = req.rsId,
              commonName = req.name
            )

            variantRepository.createVariant(newVariant).map { newId =>
              VariantOperationResult(
                name = req.name,
                rsId = req.rsId,
                status = "success",
                message = Some(s"Created build for ${req.refGenome}"),
                variantId = Some(newId)
              )
            }.recover { case e: Exception =>
              logger.error(s"Failed to create variant build: ${e.getMessage}", e)
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

  private def processUpdateRsIdRequest(req: UpdateVariantRsIdRequest): Future[VariantOperationResult] = {
    // Find all variants with this name
    variantRepository.searchByName(req.name).flatMap { variants =>
      if (variants.isEmpty) {
        Future.successful(VariantOperationResult(
          name = Some(req.name),
          rsId = Some(req.rsId),
          status = "error",
          message = Some(s"No variant found with name '${req.name}'")
        ))
      } else {
        // Update rsId on all matching variants (all builds of this variant)
        val updateFutures = variants.map { v =>
          variantRepository.update(v.copy(rsId = Some(req.rsId)))
        }

        Future.sequence(updateFutures).map { results =>
          val updatedCount = results.count(_ == true)
          VariantOperationResult(
            name = Some(req.name),
            rsId = Some(req.rsId),
            status = "success",
            message = Some(s"Updated rsId on $updatedCount variant record(s)"),
            variantId = variants.headOption.flatMap(_.variantId)
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
    }
  }

  // ============================================================================
  // Alias Source Management Endpoints
  // ============================================================================

  /**
   * Bulk update alias sources by prefix pattern.
   * Example: Update all "FGC*" aliases from source "migration" to source "FGC".
   */
  def bulkUpdateAliasSources(): Action[BulkUpdateAliasSourcesRequest] =
    secureApi.jsonAction[BulkUpdateAliasSourcesRequest].async { request =>
      val updates = request.body.updates
      logger.info(s"Bulk update alias sources request for ${updates.size} prefix patterns")

      val resultFutures = updates.map { req =>
        variantAliasRepository.bulkUpdateSourceByPrefix(req.aliasPrefix, req.newSource, req.oldSource).map { count =>
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
   * Useful for planning source cleanup operations.
   */
  def getAliasSourceStats(): Action[AnyContent] = secureApi.async { _ =>
    for {
      sources <- variantAliasRepository.getDistinctSources()
      counts <- Future.traverse(sources)(source =>
        variantAliasRepository.countBySource(source).map(count => AliasSourceSummary(source, count))
      )
    } yield {
      val totalAliases = counts.map(_.count).sum
      Ok(Json.toJson(AliasSourceStatsResponse(
        sources = counts.sortBy(-_.count),
        totalAliases = totalAliases
      )))
    }
  }

  /**
   * Preview how many aliases would be affected by a source update.
   * Useful for dry-run before actual update.
   */
  def previewAliasSourceUpdate(aliasPrefix: String, currentSource: String): Action[AnyContent] = secureApi.async { _ =>
    variantAliasRepository.countByPrefixAndSource(aliasPrefix, currentSource).map { count =>
      Ok(Json.obj(
        "aliasPrefix" -> aliasPrefix,
        "currentSource" -> currentSource,
        "matchingAliases" -> count
      ))
    }
  }
}
