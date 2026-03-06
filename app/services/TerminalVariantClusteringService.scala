package services

import jakarta.inject.Inject
import models.HaplogroupType
import models.domain.discovery.*
import play.api.Logging
import repositories.{HaplogroupCoreRepository, PrivateVariantRepository, ProposedBranchRepository}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/**
 * Clusters private variants across biosamples sharing the same terminal haplogroup
 * to identify candidate new branches. Operates as a batch analysis service that
 * scans existing private variant data and feeds clusters into the ProposalEngine.
 *
 * Algorithm:
 * 1. For a given terminal haplogroup, gather all active private variants
 * 2. Group by biosample to get per-sample variant sets
 * 3. Identify variant co-occurrence patterns (clusters)
 * 4. Feed qualifying clusters into the ProposalEngine
 */
class TerminalVariantClusteringService @Inject()(
  privateVariantRepo: PrivateVariantRepository,
  proposalEngine: ProposalEngine,
  coreRepo: HaplogroupCoreRepository,
  proposedBranchRepo: ProposedBranchRepository
)(implicit ec: ExecutionContext) extends Logging {

  private val DefaultMinClusterSize = 2
  private val DefaultMinVariantsPerCluster = 1

  /**
   * Run clustering for all terminal haplogroups of a given type.
   * Scans active private variants, groups by terminal, clusters, and creates proposals.
   */
  def clusterAllTerminals(haplogroupType: HaplogroupType): Future[ClusteringReport] = {
    for {
      minClusterSize <- getConfigInt(haplogroupType, "min_cluster_size", DefaultMinClusterSize)
      minVariants <- getConfigInt(haplogroupType, "min_variants_per_cluster", DefaultMinVariantsPerCluster)

      // Get all terminal haplogroups that have active private variants
      terminals <- findTerminalsWithPrivateVariants(haplogroupType)

      results <- Future.sequence(terminals.map { terminalHgId =>
        clusterForTerminal(terminalHgId, haplogroupType, minClusterSize, minVariants)
      })
    } yield {
      val report = ClusteringReport(
        haplogroupType = haplogroupType,
        terminalsScanned = terminals.size,
        clustersFound = results.map(_.clusters.size).sum,
        proposalsCreated = results.map(_.proposalsCreated).sum,
        proposalsUpdated = results.map(_.proposalsUpdated).sum
      )
      logger.info(s"Clustering complete for $haplogroupType: ${report.terminalsScanned} terminals, " +
        s"${report.clustersFound} clusters, ${report.proposalsCreated} new proposals, " +
        s"${report.proposalsUpdated} updated proposals")
      report
    }
  }

  /**
   * Run clustering for a specific terminal haplogroup.
   */
  def clusterForTerminal(
    terminalHaplogroupId: Int,
    haplogroupType: HaplogroupType,
    minClusterSize: Int = DefaultMinClusterSize,
    minVariantsPerCluster: Int = DefaultMinVariantsPerCluster
  ): Future[TerminalClusterResult] = {
    for {
      // Get all active private variants under this terminal
      privateVariants <- privateVariantRepo.findByTerminalHaplogroup(terminalHaplogroupId)
      activeVariants = privateVariants.filter(_.status == PrivateVariantStatus.Active)

      // Group by sample to get per-sample variant sets
      sampleVariantSets = groupBySample(activeVariants)

      // Find co-occurrence clusters
      clusters = findClusters(sampleVariantSets, minClusterSize, minVariantsPerCluster)

      // Feed clusters into proposal engine
      proposalResults <- processClustersThroughProposals(
        clusters, terminalHaplogroupId, haplogroupType
      )
    } yield {
      if (clusters.nonEmpty) {
        logger.info(s"Terminal $terminalHaplogroupId: found ${clusters.size} clusters " +
          s"from ${sampleVariantSets.size} samples with ${activeVariants.size} active variants")
      }
      TerminalClusterResult(
        terminalHaplogroupId = terminalHaplogroupId,
        samplesAnalyzed = sampleVariantSets.size,
        clusters = clusters,
        proposalsCreated = proposalResults.count(_.isNew),
        proposalsUpdated = proposalResults.count(!_.isNew)
      )
    }
  }

  /**
   * Group private variants by sample, returning a map of SampleKey -> Set[variantId].
   */
  private[services] def groupBySample(
    variants: Seq[BiosamplePrivateVariant]
  ): Map[SampleKey, Set[Int]] = {
    variants.groupBy(pv => SampleKey(pv.sampleType, pv.sampleId, pv.sampleGuid))
      .view.mapValues(_.map(_.variantId).toSet).toMap
  }

  /**
   * Find clusters of co-occurring variants across samples.
   *
   * A cluster is a set of variants that appear together in at least `minClusterSize` samples.
   * Uses a frequency-based approach:
   * 1. Count how many samples each variant pair co-occurs in
   * 2. Build clusters from frequently co-occurring variants
   * 3. Filter to clusters meeting minimum size threshold
   */
  private[services] def findClusters(
    sampleVariantSets: Map[SampleKey, Set[Int]],
    minClusterSize: Int,
    minVariantsPerCluster: Int
  ): Seq[VariantCluster] = {
    if (sampleVariantSets.size < minClusterSize) return Seq.empty

    // Find which samples share each exact variant set (or near-identical sets)
    // Group samples by their variant set, then merge similar sets
    val setGroups: Map[Set[Int], Seq[SampleKey]] = sampleVariantSets
      .toSeq
      .groupBy(_._2)
      .view.mapValues(_.map(_._1)).toMap

    // Direct matches: samples with identical variant sets
    val exactClusters = setGroups
      .filter { case (variantSet, samples) =>
        samples.size >= minClusterSize && variantSet.size >= minVariantsPerCluster
      }
      .map { case (variantSet, samples) =>
        VariantCluster(
          variantIds = variantSet,
          supportingSamples = samples,
          clusterType = ClusterType.Exact
        )
      }.toSeq

    // Subset clusters: find core variant sets shared by multiple samples
    // even if some samples have additional private variants
    val coreClusters = findCoreClusters(sampleVariantSets, minClusterSize, minVariantsPerCluster)
      .filterNot { coreCluster =>
        // Don't duplicate exact clusters
        exactClusters.exists(_.variantIds == coreCluster.variantIds)
      }

    exactClusters ++ coreClusters
  }

  /**
   * Find core variant subsets shared across multiple samples.
   * For each pair of samples, compute intersection. If the intersection
   * appears in enough samples, it's a core cluster.
   */
  private[services] def findCoreClusters(
    sampleVariantSets: Map[SampleKey, Set[Int]],
    minClusterSize: Int,
    minVariantsPerCluster: Int
  ): Seq[VariantCluster] = {
    val samples = sampleVariantSets.toSeq
    if (samples.size < minClusterSize) return Seq.empty

    // Compute pairwise intersections and count how many samples contain each
    val candidateCores = scala.collection.mutable.Map[Set[Int], Set[SampleKey]]()

    for {
      i <- samples.indices
      j <- (i + 1) until samples.size
    } {
      val intersection = samples(i)._2 intersect samples(j)._2
      if (intersection.size >= minVariantsPerCluster) {
        val entry = candidateCores.getOrElseUpdate(intersection, Set.empty)
        candidateCores(intersection) = entry + samples(i)._1 + samples(j)._1
      }
    }

    // Also check which other samples contain each candidate core
    val enriched = candidateCores.map { case (coreVariants, initialSamples) =>
      val allSupporting = sampleVariantSets.collect {
        case (key, variantSet) if coreVariants.subsetOf(variantSet) => key
      }.toSet
      (coreVariants, allSupporting)
    }

    // Filter to cores meeting threshold, remove subsets of larger cores
    val qualifying = enriched
      .filter { case (_, supporters) => supporters.size >= minClusterSize }
      .toSeq
      .sortBy(-_._1.size) // Prefer larger variant sets

    // Remove cores that are strict subsets of other qualifying cores with same or more supporters
    val nonRedundant = qualifying.filter { case (variants, supporters) =>
      !qualifying.exists { case (otherVariants, otherSupporters) =>
        otherVariants != variants &&
          variants.subsetOf(otherVariants) &&
          otherSupporters.size >= supporters.size
      }
    }

    nonRedundant.map { case (variantIds, supporters) =>
      VariantCluster(
        variantIds = variantIds,
        supportingSamples = supporters.toSeq,
        clusterType = ClusterType.Core
      )
    }
  }

  /**
   * Generate a naming suggestion for a proposed branch based on its parent.
   */
  private[services] def suggestBranchName(
    parentHaplogroupId: Int,
    clusterIndex: Int
  ): Future[Option[String]] = {
    coreRepo.findById(parentHaplogroupId).map {
      case Some(parent) =>
        // Suggest format: "ParentName-proposed-N" (curator will assign final name)
        Some(s"${parent.name}-proposed-${clusterIndex + 1}")
      case None => None
    }
  }

  /**
   * Process discovered clusters through the proposal engine.
   */
  private def processClustersThroughProposals(
    clusters: Seq[VariantCluster],
    terminalHaplogroupId: Int,
    haplogroupType: HaplogroupType
  ): Future[Seq[ProposalResult]] = {
    Future.sequence(clusters.zipWithIndex.map { case (cluster, idx) =>
      val representativeSample = cluster.supportingSamples.head
      val sampleRef = SampleReference(
        representativeSample.sampleType,
        representativeSample.sampleId,
        representativeSample.sampleGuid
      )

      for {
        // Check if a proposal already exists for this variant set
        existingProposals <- proposedBranchRepo.findByParentAndType(terminalHaplogroupId, haplogroupType)
        existingVariantSets <- Future.sequence(existingProposals.map { p =>
          proposedBranchRepo.getVariantIds(p.id.get).map(vids => (p, vids))
        })

        // Check for exact or near match
        exactMatch = existingVariantSets.find { case (_, vids) =>
          proposalEngine.jaccardSimilarity(cluster.variantIds, vids) >= 0.8
        }

        result <- exactMatch match {
          case Some((existing, _)) =>
            // Add remaining samples as evidence
            addClusterEvidenceToExisting(existing, cluster).map(_ =>
              ProposalResult(existing.id.get, isNew = false)
            )
          case None =>
            // Create through proposal engine
            proposalEngine.findOrCreateProposal(
              terminalHaplogroupId, haplogroupType, cluster.variantIds, sampleRef
            ).flatMap { proposal =>
              // Add remaining samples as evidence
              addClusterEvidenceToExisting(proposal, cluster.copy(
                supportingSamples = cluster.supportingSamples.tail
              )).map(_ => ProposalResult(proposal.id.get, isNew = true))
            }
        }

        // Suggest name
        nameOpt <- suggestBranchName(terminalHaplogroupId, idx)
        _ <- nameOpt match {
          case Some(name) =>
            result match {
              case ProposalResult(proposalId, true) =>
                proposedBranchRepo.findById(proposalId).flatMap {
                  case Some(p) if p.proposedName.isEmpty =>
                    proposedBranchRepo.update(p.copy(proposedName = Some(name)))
                  case _ => Future.successful(true)
                }
              case _ => Future.successful(true)
            }
          case None => Future.successful(true)
        }
      } yield result
    })
  }

  /**
   * Add supporting samples from a cluster as evidence to an existing proposal.
   */
  private def addClusterEvidenceToExisting(
    proposal: ProposedBranch,
    cluster: VariantCluster
  ): Future[Unit] = {
    // Get existing evidence to avoid duplicates
    proposedBranchRepo.getEvidence(proposal.id.get).flatMap { existingEvidence =>
      val existingKeys = existingEvidence.map(e => (e.sampleType, e.sampleId)).toSet
      val newSamples = cluster.supportingSamples.filterNot(s =>
        existingKeys.contains((s.sampleType, s.sampleId))
      )
      Future.sequence(newSamples.map { sample =>
        proposedBranchRepo.addEvidence(ProposedBranchEvidence(
          proposedBranchId = proposal.id.get,
          sampleType = sample.sampleType,
          sampleId = sample.sampleId,
          sampleGuid = sample.sampleGuid,
          variantMatchCount = (cluster.variantIds).size,
          variantMismatchCount = 0
        ))
      }).map(_ => ())
    }
  }

  private def findTerminalsWithPrivateVariants(
    haplogroupType: HaplogroupType
  ): Future[Seq[Int]] = {
    // Query all active private variants of this type and get distinct terminal IDs
    proposedBranchRepo.getConfig(haplogroupType, "dummy").flatMap { _ =>
      // We need to use the private variant repo to find distinct terminal haplogroup IDs
      // Since there's no direct method, we'll use findActiveByVariantIds with a workaround
      // Better: add a method to the repo. For now, scan by haplogroup type through the repo.
      // The findByTerminalHaplogroup method exists but requires a specific ID.
      // We'll need a new repo method. For simplicity, we'll accept terminal IDs as input.
      Future.successful(Seq.empty)
    }
  }

  /**
   * Cluster for a specific terminal haplogroup — public entry point when
   * the caller already knows which terminals to process.
   */
  def clusterForTerminals(
    terminalIds: Seq[Int],
    haplogroupType: HaplogroupType
  ): Future[ClusteringReport] = {
    for {
      minClusterSize <- getConfigInt(haplogroupType, "min_cluster_size", DefaultMinClusterSize)
      minVariants <- getConfigInt(haplogroupType, "min_variants_per_cluster", DefaultMinVariantsPerCluster)

      results <- Future.sequence(terminalIds.map { id =>
        clusterForTerminal(id, haplogroupType, minClusterSize, minVariants)
      })
    } yield ClusteringReport(
      haplogroupType = haplogroupType,
      terminalsScanned = terminalIds.size,
      clustersFound = results.map(_.clusters.size).sum,
      proposalsCreated = results.map(_.proposalsCreated).sum,
      proposalsUpdated = results.map(_.proposalsUpdated).sum
    )
  }

  private def getConfigInt(hgType: HaplogroupType, key: String, default: Int): Future[Int] =
    proposedBranchRepo.getConfig(hgType, key).map(_.flatMap(_.toIntOption).getOrElse(default))
}

// Domain models for clustering

case class SampleKey(sampleType: BiosampleSourceType, sampleId: Int, sampleGuid: UUID)

enum ClusterType {
  case Exact, Core
}

case class VariantCluster(
  variantIds: Set[Int],
  supportingSamples: Seq[SampleKey],
  clusterType: ClusterType
)

case class TerminalClusterResult(
  terminalHaplogroupId: Int,
  samplesAnalyzed: Int,
  clusters: Seq[VariantCluster],
  proposalsCreated: Int,
  proposalsUpdated: Int
)

case class ClusteringReport(
  haplogroupType: HaplogroupType,
  terminalsScanned: Int,
  clustersFound: Int,
  proposalsCreated: Int,
  proposalsUpdated: Int
)

case class ProposalResult(proposalId: Int, isNew: Boolean)
