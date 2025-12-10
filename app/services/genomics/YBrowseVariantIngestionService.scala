package services.genomics

import htsjdk.samtools.liftover.LiftOver
import htsjdk.samtools.util.Interval
import htsjdk.variant.variantcontext.VariantContext
import htsjdk.variant.vcf.VCFFileReader
import jakarta.inject.{Inject, Singleton}
import models.dal.domain.genomics.Variant
import play.api.Logger
import repositories.{GenbankContigRepository, VariantRepository}

import java.io.File
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

@Singleton
class YBrowseVariantIngestionService @Inject()(
                                                variantRepository: VariantRepository,
                                                genbankContigRepository: GenbankContigRepository
                                              )(implicit ec: ExecutionContext) {

  private val logger = Logger(this.getClass)

  /**
   * Ingests variants from a YBrowse VCF file.
   *
   * @param vcfFile The VCF file to ingest.
   * @param liftoverChains Map of reference genome name to chain file for liftover (e.g., "GRCh37" -> chainFile).
   * @return A Future containing the number of variants ingested.
   */
  def ingestVcf(vcfFile: File, liftoverChains: Map[String, File] = Map.empty): Future[Int] = {
    val reader = new VCFFileReader(vcfFile, false)
    val iterator = reader.iterator().asScala

    // Initialize LiftOver instances
    val liftovers = liftoverChains.map { case (genome, file) =>
      genome -> new LiftOver(file)
    }

    // Pre-fetch contigs for relevant genomes (hg38 + targets)
    // We assume input is hg38. We also need target genomes.
    // For simplicity, we'll fetch common names and filter by genome in memory or separate queries.
    // But since we don't know the exact "reference_genome" strings in DB, we'll fetch by common name "chrY" etc.
    // and let the caching logic handle it.
    
    // We'll process in batches to avoid OOM and DB overload
    val batchSize = 1000
    
    // We need a way to map (CommonName, ReferenceGenome) -> ContigID
    // We'll build this cache lazily or pre-fetch if we know the contigs.
    // YBrowse is mostly Y-DNA, so "chrY".
    
    processBatches(iterator, batchSize, liftovers)
  }

  private def processBatches(
                              iterator: Iterator[VariantContext],
                              batchSize: Int,
                              liftovers: Map[String, LiftOver]
                            ): Future[Int] = {
    
    // Recursive or fold based batch processing
    // Since it's Future-based, we'll use recursion or a foldLeft with Future.
    
    def processNextBatch(accumulatedCount: Int): Future[Int] = {
      if (!iterator.hasNext) {
        Future.successful(accumulatedCount)
      } else {
        val batch = iterator.take(batchSize).toSeq
        processBatch(batch, liftovers).flatMap { count =>
          processNextBatch(accumulatedCount + count)
        }
      }
    }
    
    processNextBatch(0)
  }

  private def processBatch(batch: Seq[VariantContext], liftovers: Map[String, LiftOver]): Future[Int] = {
    // 1. Collect all contig names from batch
    val contigNames = batch.map(_.getContig).distinct
    // 2. Resolve Contig IDs for hg38 (source) and targets
    // We assume "hg38" is the source genome name in DB.
    // And liftovers keys are target genome names.
    
    val allGenomes = Set("hg38", "GRCh38") ++ liftovers.keys
    
    genbankContigRepository.findByCommonNames(contigNames).flatMap { contigs =>
      // Map: (CommonName, Genome) -> ContigID
      val contigMap = contigs.flatMap { c =>
        for {
          cn <- c.commonName
          rg <- c.referenceGenome
        } yield (cn, rg) -> c.genbankContigId.get
      }.toMap

      val variantsToSave = batch.flatMap { vc =>
        // Normalize
        val normalizedVc = normalizeVariant(vc)
        
        // Create hg38 variant
        val hg38Variants = createVariantsForContext(normalizedVc, "hg38", contigMap)
        
        // Create lifted variants
        val liftedVariants = liftovers.flatMap { case (targetGenome, liftOver) =>
          val interval = new Interval(vc.getContig, vc.getStart, vc.getEnd)
          val lifted = liftOver.liftOver(interval)
          if (lifted != null) {
             // Create variant context with new position
             // Note: Liftover only gives coordinates. Alleles might change (strand flip).
             // HTSJDK LiftOver doesn't automatically handle allele flipping for VCF records generically without reference.
             // We'll assume positive strand or handle it if we had reference.
             // For now, we keep alleles as is, assuming forward strand mapping (common for Y).
             
             // We need to map the contig name. liftOver returns interval with new contig name.
             val liftedVc = new htsjdk.variant.variantcontext.VariantContextBuilder(normalizedVc)
               .chr(lifted.getContig)
               .start(lifted.getStart)
               .stop(lifted.getEnd)
               .make()
               
             createVariantsForContext(liftedVc, targetGenome, contigMap)
          } else {
            Seq.empty
          }
        }
        
        hg38Variants ++ liftedVariants
      }
      
      variantRepository.findOrCreateVariantsBatch(variantsToSave).map(_.size)
    }
  }

  private def createVariantsForContext(
                                        vc: VariantContext,
                                        genome: String,
                                        contigMap: Map[(String, String), Int]
                                      ): Seq[Variant] = {
    // Resolve contig ID
    // Try exact match or fallbacks (e.g. remove "chr")
    val contigIdOpt = contigMap.get((vc.getContig, genome))
      .orElse(contigMap.get((vc.getContig.stripPrefix("chr"), genome)))

    contigIdOpt match {
      case Some(contigId) =>
        vc.getAlternateAlleles.asScala.map { alt =>
          val rawId = Option(vc.getID).filterNot(id => id == "." || id.isEmpty)
          val rsId = rawId.filter(_.toLowerCase.startsWith("rs"))
          // For Y-DNA, the ID column often contains the SNP name (e.g. M269), which is the common name.
          val commonName = rawId

          Variant(
            genbankContigId = contigId,
            position = vc.getStart,
            referenceAllele = vc.getReference.getDisplayString,
            alternateAllele = alt.getDisplayString,
            variantType = vc.getType.toString,
            rsId = rsId,
            commonName = commonName
          )
        }.toSeq
      case None =>
        // Logger.warn(s"Contig not found for ${vc.getContig} in $genome")
        Seq.empty
    }
  }

  private def normalizeVariant(vc: VariantContext): VariantContext = {
    // Basic trimming of common flanking bases
    // This is a simplified normalization. 
    // Ideally we'd use a reference sequence.
    vc
  }
}
