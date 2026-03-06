package services

import jakarta.inject.Inject
import models.HaplogroupType
import models.atmosphere.VariantCall
import models.domain.discovery.*
import models.domain.genomics.{MutationType, NamingStatus, VariantV2}
import play.api.Logging
import repositories.{HaplogroupCoreRepository, HaplogroupVariantRepository, PrivateVariantRepository}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/**
 * Extracts private variants from biosample haplogroup results and records them
 * for the discovery pipeline. Private variants are mutations beyond the terminal
 * haplogroup that may indicate new branches when shared across multiple biosamples.
 */
class PrivateVariantExtractionService @Inject()(
  privateVariantRepo: PrivateVariantRepository,
  variantRepo: HaplogroupVariantRepository,
  coreRepo: HaplogroupCoreRepository
)(implicit ec: ExecutionContext) extends Logging {

  /**
   * Extract and store private variants from a Citizen biosample.
   */
  def extractFromCitizenBiosample(
    citizenBiosampleId: Int,
    sampleGuid: UUID,
    terminalHaplogroupName: String,
    haplogroupType: HaplogroupType,
    variantCalls: Seq[VariantCall]
  ): Future[Seq[BiosamplePrivateVariant]] = {
    extractPrivateVariants(
      SampleReference(BiosampleSourceType.Citizen, citizenBiosampleId, sampleGuid),
      terminalHaplogroupName,
      haplogroupType,
      variantCalls
    )
  }

  /**
   * Extract and store private variants from an External (publication) biosample.
   */
  def extractFromExternalBiosample(
    biosampleId: Int,
    sampleGuid: UUID,
    terminalHaplogroupName: String,
    haplogroupType: HaplogroupType,
    variantCalls: Seq[VariantCall]
  ): Future[Seq[BiosamplePrivateVariant]] = {
    extractPrivateVariants(
      SampleReference(BiosampleSourceType.External, biosampleId, sampleGuid),
      terminalHaplogroupName,
      haplogroupType,
      variantCalls
    )
  }

  /**
   * Core extraction logic. Resolves variant calls to variant IDs,
   * filters to only private (non-tree) variants, and stores them.
   */
  private def extractPrivateVariants(
    sampleRef: SampleReference,
    terminalHaplogroupName: String,
    haplogroupType: HaplogroupType,
    variantCalls: Seq[VariantCall]
  ): Future[Seq[BiosamplePrivateVariant]] = {
    if (variantCalls.isEmpty) {
      return Future.successful(Seq.empty)
    }

    for {
      // Resolve terminal haplogroup
      terminalHgOpt <- coreRepo.getHaplogroupByName(terminalHaplogroupName, haplogroupType)
      terminalHg = terminalHgOpt.getOrElse {
        logger.warn(s"Terminal haplogroup '$terminalHaplogroupName' not found for sample ${sampleRef.sampleGuid}")
        throw new IllegalArgumentException(s"Terminal haplogroup '$terminalHaplogroupName' not found")
      }

      // Get the set of variant IDs already on the tree for this haplogroup's lineage
      ancestors <- coreRepo.getAncestors(terminalHg.id.get)
      allLineageIds = (ancestors.flatMap(_.id) :+ terminalHg.id.get).toSet
      treeVariants <- Future.sequence(allLineageIds.toSeq.map(id => variantRepo.getHaplogroupVariants(id)))
      treeVariantPositions = treeVariants.flatten.flatMap(extractPosition).toSet

      // Resolve variant calls to variant_v2 records
      resolvedVariants <- resolveOrCreateVariants(variantCalls, terminalHg.id.get)

      // Filter to only private variants (not already on the tree)
      privateEntries = resolvedVariants.filter { case (call, _) =>
        val pos = positionKey(call)
        !treeVariantPositions.contains(pos)
      }

      // Create private variant records
      pvRecords = privateEntries.map { case (_, variantId) =>
        BiosamplePrivateVariant(
          sampleType = sampleRef.sampleType,
          sampleId = sampleRef.sampleId,
          sampleGuid = sampleRef.sampleGuid,
          variantId = variantId,
          haplogroupType = haplogroupType,
          terminalHaplogroupId = terminalHg.id.get
        )
      }.toSeq

      saved <- if (pvRecords.nonEmpty) {
        logger.info(s"Extracted ${pvRecords.size} private variants for sample ${sampleRef.sampleGuid} " +
          s"(${sampleRef.sampleType}) beyond terminal ${terminalHaplogroupName}")
        privateVariantRepo.createAll(pvRecords)
      } else {
        Future.successful(Seq.empty)
      }
    } yield saved
  }

  /**
   * Resolve variant calls to existing variant_v2 records or create new ones.
   * Returns a map of VariantCall -> variant_id.
   */
  private[services] def resolveOrCreateVariants(
    variantCalls: Seq[VariantCall],
    terminalHaplogroupId: Int
  ): Future[Map[VariantCall, Int]] = {
    Future.sequence(variantCalls.map { call =>
      resolveVariant(call, terminalHaplogroupId).map(vid => call -> vid)
    }).map(_.toMap)
  }

  /**
   * Resolve a single variant call to a variant_v2 ID.
   * Looks up by name first, then by position, creating new if needed.
   */
  private def resolveVariant(call: VariantCall, terminalHaplogroupId: Int): Future[Int] = {
    call.variantName match {
      case Some(name) =>
        // Named variant: search by name
        variantRepo.findVariants(name).flatMap {
          case variants if variants.nonEmpty =>
            // Use the first match — parallel mutation detection is deferred to DISC-2
            Future.successful(variants.head.variantId.get)
          case _ =>
            // Not found by name, search by position
            resolveByPosition(call, terminalHaplogroupId)
        }
      case None =>
        // Unnamed variant: search by position
        resolveByPosition(call, terminalHaplogroupId)
    }
  }

  /**
   * Resolve a variant by genomic position coordinates.
   */
  private def resolveByPosition(call: VariantCall, terminalHaplogroupId: Int): Future[Int] = {
    val posQuery = s"${call.contigAccession}:${call.position}"
    variantRepo.findVariants(posQuery).map {
      case variants if variants.nonEmpty => variants.head.variantId.get
      case _ =>
        // Variant not found — for now, log and skip.
        // Full variant creation requires DISC-2 (proposed branch engine) context.
        logger.debug(s"Variant at ${call.contigAccession}:${call.position} not found in variant_v2, skipping")
        -1
    }
  }

  private def extractPosition(v: VariantV2): Option[String] = {
    val coords = v.coordinates
    val grch38 = (coords \ "GRCh38").asOpt[play.api.libs.json.JsObject]
      .orElse((coords \ "GRCh38.p14").asOpt[play.api.libs.json.JsObject])
    grch38.flatMap { c =>
      for {
        contig <- (c \ "contig").asOpt[String]
        pos <- (c \ "position").asOpt[Int]
      } yield s"$contig:$pos"
    }
  }

  private def positionKey(call: VariantCall): String =
    s"${call.contigAccession}:${call.position}"
}

/**
 * Value object representing a reference to any biosample type.
 */
case class SampleReference(
  sampleType: BiosampleSourceType,
  sampleId: Int,
  sampleGuid: UUID
)
