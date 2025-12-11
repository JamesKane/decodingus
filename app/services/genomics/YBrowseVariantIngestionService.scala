package services.genomics

import config.GenomicsConfig
import htsjdk.samtools.liftover.LiftOver
import htsjdk.samtools.reference.{ReferenceSequenceFile, ReferenceSequenceFileFactory}
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
import scala.util.{Try, Success, Failure}

@Singleton
class YBrowseVariantIngestionService @Inject()(
                                                variantRepository: VariantRepository,
                                                genbankContigRepository: GenbankContigRepository,
                                                genomicsConfig: GenomicsConfig
                                              )(implicit ec: ExecutionContext) {

  private val logger = Logger(this.getClass)

  // Lazy-load ReferenceSequenceFile for each configured reference genome
  private val referenceFastaFiles: Map[String, ReferenceSequenceFile] = genomicsConfig.fastaPaths.flatMap {
    case (genome, fastaFile) =>
      if (fastaFile.exists()) {
        logger.info(s"Loading reference FASTA for $genome from ${fastaFile.getPath}")
        Some(genome -> ReferenceSequenceFileFactory.getReferenceSequenceFile(fastaFile))
      } else {
        logger.warn(s"Reference FASTA file for $genome not found at ${fastaFile.getPath}. Normalization might be incomplete.")
        None
      }
  }

  /**
   * Ingests variants from a YBrowse VCF file.
   *
   * @param vcfFile The VCF file to ingest.
   * @param sourceGenome The reference genome of the input VCF (default: "GRCh38").
   * @return A Future containing the number of variants ingested.
   */
  def ingestVcf(vcfFile: File, sourceGenome: String = "GRCh38"): Future[Int] = {
    val reader = new VCFFileReader(vcfFile, false)
    val iterator = reader.iterator().asScala

    // Resolve canonical source genome name
    val canonicalSource = genomicsConfig.resolveReferenceName(sourceGenome)

    // Identify target genomes (all supported except source)
    val targetGenomes = genomicsConfig.supportedReferences.filter(_ != canonicalSource)

    // Load available liftover chains
    val liftovers: Map[String, LiftOver] = targetGenomes.flatMap { target =>
      genomicsConfig.getLiftoverChainFile(canonicalSource, target) match {
        case Some(file) if file.exists() =>
          logger.info(s"Loaded liftover chain for $canonicalSource -> $target: ${file.getPath}")
          Some(target -> new LiftOver(file))
        case Some(file) =>
          logger.warn(s"Liftover chain file defined for $canonicalSource -> $target but not found at: ${file.getPath}")
          None
        case None =>
          logger.debug(s"No liftover chain defined for $canonicalSource -> $target")
          None
      }
    }.toMap
    
    val batchSize = 1000
    
    processBatches(iterator, batchSize, liftovers, canonicalSource)
  }

  private def processBatches(
                              iterator: Iterator[VariantContext],
                              batchSize: Int,
                              liftovers: Map[String, LiftOver],
                              sourceGenome: String
                            ): Future[Int] = {

    val progressInterval = 100 // Log progress every 100 batches (100k records)

    def processNextBatch(accumulatedCount: Int, skippedCount: Int, batchNumber: Int): Future[Int] = {
      if (!iterator.hasNext) {
        logger.info(s"Ingestion complete. Processed $accumulatedCount variants" +
          (if (skippedCount > 0) s", skipped $skippedCount malformed records." else "."))
        Future.successful(accumulatedCount)
      } else {
        // Safely materialize records, skipping malformed ones
        val (batch, newSkipped) = safelyTakeBatch(iterator, batchSize)
        processBatch(batch, liftovers, sourceGenome).flatMap { count =>
          val newTotal = accumulatedCount + count
          val newBatchNumber = batchNumber + 1

          // Log progress every N batches
          if (newBatchNumber % progressInterval == 0) {
            val recordsProcessed = newBatchNumber * batchSize
            logger.info(s"Progress: processed ~$recordsProcessed VCF records, created/updated $newTotal variants...")
          }

          processNextBatch(newTotal, skippedCount + newSkipped, newBatchNumber)
        }
      }
    }

    logger.info(s"Starting variant ingestion (batch size: $batchSize, progress logged every ${progressInterval * batchSize} records)")
    processNextBatch(0, 0, 0)
  }

  /**
   * Safely takes a batch of records from the iterator, skipping malformed records.
   * HTSJDK may throw TribbleException for malformed VCF lines (e.g., duplicate alleles).
   *
   * @param iterator The VCF record iterator
   * @param batchSize Maximum number of valid records to collect
   * @return Tuple of (validRecords, skippedCount)
   */
  private def safelyTakeBatch(iterator: Iterator[VariantContext], batchSize: Int): (Seq[VariantContext], Int) = {
    val batch = scala.collection.mutable.ArrayBuffer[VariantContext]()
    var skipped = 0

    while (batch.size < batchSize && iterator.hasNext) {
      Try(iterator.next()) match {
        case Success(vc) => batch += vc
        case Failure(e) =>
          skipped += 1
          if (skipped <= 10) {
            logger.warn(s"Skipping malformed VCF record: ${e.getMessage}")
          } else if (skipped == 11) {
            logger.warn("Suppressing further malformed record warnings...")
          }
      }
    }

    (batch.toSeq, skipped)
  }

  private def processBatch(batch: Seq[VariantContext], liftovers: Map[String, LiftOver], sourceGenome: String): Future[Int] = {
    // 1. Collect all contig names from batch
    val contigNames = batch.map(_.getContig).distinct
    // 2. Resolve Contig IDs for hg38 (source) and targets

    genbankContigRepository.findByCommonNames(contigNames).flatMap { contigs =>
      // Map: (CommonName, Genome) -> ContigID
      val contigMap = contigs.flatMap { c =>
        for {
          cn <- c.commonName
          rg <- c.referenceGenome
        } yield (cn, rg) -> c.id.get
      }.toMap

      // Debug: log contig mapping on first batch
      if (contigMap.isEmpty && batch.nonEmpty) {
        logger.warn(s"Contig mapping failed! Looking for: ${contigNames.mkString(", ")}. Found contigs: ${contigs.map(c => s"${c.commonName}/${c.referenceGenome}").mkString(", ")}")
      }

      // Separate source variants (get aliases) from lifted variants (no aliases)
      val sourceVariants = batch.flatMap { vc =>
        createVariantsForContext(vc, sourceGenome, contigMap)
      }

      val liftedVariants = batch.flatMap { vc =>
        liftovers.flatMap { case (targetGenome, liftOver) =>
          val interval = new Interval(vc.getContig, vc.getStart, vc.getEnd)
          val lifted = liftOver.liftOver(interval)
          if (lifted != null) {
            val liftedVc = new htsjdk.variant.variantcontext.VariantContextBuilder(vc)
              .chr(lifted.getContig)
              .start(lifted.getStart)
              .stop(lifted.getEnd)
              .make()
            // Clear the ID for lifted variants - they share the source variant's name
            createVariantsForContext(liftedVc, targetGenome, contigMap).map(_.copy(commonName = None, rsId = None))
          } else {
            Seq.empty
          }
        }
      }

      // Debug: log if we're losing variants
      if (sourceVariants.isEmpty && batch.nonEmpty) {
        logger.warn(s"No variants to save from batch of ${batch.size} records! Source genome: $sourceGenome, contigMap keys: ${contigMap.keys.mkString(", ")}")
      }

      // Create source variants with aliases, lifted variants without
      for {
        sourceCount <- variantRepository.findOrCreateVariantsBatchWithAliases(sourceVariants, "ybrowse")
        liftedCount <- if (liftedVariants.nonEmpty) variantRepository.findOrCreateVariantsBatchNoAliases(liftedVariants) else Future.successful(Seq.empty)
      } yield sourceCount.size + liftedCount.size
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

          // Normalize the variant (left-align INDELs)
          val refSeq = referenceFastaFiles.get(genome)
          val (normPos, normRef, normAlt) = normalizeVariant(
            vc.getContig,
            vc.getStart,
            vc.getReference.getDisplayString,
            alt.getDisplayString,
            refSeq
          )

          Variant(
            genbankContigId = contigId,
            position = normPos,
            referenceAllele = normRef,
            alternateAllele = normAlt,
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

  /**
   * Normalizes a variant by performing VCF-style left-alignment.
   *
   * The algorithm:
   * 1. Right-trim: Remove common suffix bases from ref and alt alleles
   * 2. Pad: If either allele becomes empty, prepend the preceding reference base
   * 3. Left-trim: Remove common prefix bases (keeping at least 1 base on each)
   *
   * @param contig The contig name for reference lookup
   * @param pos The 1-based position
   * @param ref The reference allele
   * @param alt The alternate allele
   * @param refSeq Optional reference sequence file for padding lookup
   * @return A tuple of (normalizedPos, normalizedRef, normalizedAlt)
   */
  private def normalizeVariant(
    contig: String,
    pos: Int,
    ref: String,
    alt: String,
    refSeq: Option[ReferenceSequenceFile]
  ): (Int, String, String) = {
    // Expand compressed repeat notation (e.g., "3T" -> "TTT")
    val expandedRef = expandRepeatNotation(ref)
    val expandedAlt = expandRepeatNotation(alt)

    // Skip normalization for SNPs (single base, same length)
    if (expandedRef.length == 1 && expandedAlt.length == 1) {
      return (pos, expandedRef, expandedAlt)
    }

    var currRef = expandedRef
    var currAlt = expandedAlt
    var currPos = pos

    // Step 1: Right-trim common suffix bases
    while (currRef.nonEmpty && currAlt.nonEmpty && currRef.last == currAlt.last) {
      currRef = currRef.dropRight(1)
      currAlt = currAlt.dropRight(1)
    }

    // Step 2: Pad with preceding base if either allele is empty
    if (currRef.isEmpty || currAlt.isEmpty) {
      currPos -= 1
      val paddingBase = refSeq match {
        case Some(rs) =>
          try {
            new String(rs.getSubsequenceAt(contig, currPos, currPos).getBases, "UTF-8")
          } catch {
            case _: Exception => "N"
          }
        case None => "N"
      }
      currRef = paddingBase + currRef
      currAlt = paddingBase + currAlt
    }

    // Step 3: Left-trim common prefix bases (keeping at least 1 base)
    while (currRef.length > 1 && currAlt.length > 1 && currRef.head == currAlt.head) {
      currRef = currRef.tail
      currAlt = currAlt.tail
      currPos += 1
    }

    (currPos, currRef, currAlt)
  }

  /**
   * Expands compressed repeat notation (e.g., "3T" -> "TTT", "2AG" -> "AGAG").
   * Returns the input unchanged if it's already a valid nucleotide sequence.
   */
  private def expandRepeatNotation(allele: String): String = {
    if (allele.forall(c => "ACGTN".contains(c.toUpper))) {
      allele
    } else {
      val (digits, bases) = allele.partition(_.isDigit)
      if (digits.nonEmpty && bases.nonEmpty) {
        bases * digits.toInt
      } else {
        bases
      }
    }
  }

  /**
   * Lifts a variant to all other supported reference genomes.
   *
   * @param sourceVariant The variant to lift (must have genbankContigId resolved)
   * @param sourceContig The source contig information
   * @return Future containing lifted variants for each target genome (may be empty if liftover fails)
   */
  def liftoverVariant(sourceVariant: Variant, sourceContig: models.domain.genomics.GenbankContig): Future[Seq[Variant]] = {
    val sourceGenome = sourceContig.referenceGenome.getOrElse("GRCh38")
    val canonicalSource = genomicsConfig.resolveReferenceName(sourceGenome)
    val sourceContigName = sourceContig.commonName.getOrElse(sourceContig.accession)

    // Get target genomes (all supported except source)
    val targetGenomes = genomicsConfig.supportedReferences.filter(_ != canonicalSource)

    // Load liftover chains for each target
    val liftoverResults = targetGenomes.flatMap { targetGenome =>
      genomicsConfig.getLiftoverChainFile(canonicalSource, targetGenome) match {
        case Some(chainFile) if chainFile.exists() =>
          val liftOver = new LiftOver(chainFile)
          val interval = new Interval(sourceContigName, sourceVariant.position, sourceVariant.position)
          val lifted = liftOver.liftOver(interval)

          if (lifted != null) {
            logger.info(s"Lifted ${sourceVariant.commonName.getOrElse("variant")} from $canonicalSource:$sourceContigName:${sourceVariant.position} to $targetGenome:${lifted.getContig}:${lifted.getStart}")
            Some((targetGenome, lifted.getContig, lifted.getStart))
          } else {
            logger.warn(s"Failed to liftover ${sourceVariant.commonName.getOrElse("variant")} from $canonicalSource to $targetGenome")
            None
          }
        case _ =>
          logger.debug(s"No liftover chain available for $canonicalSource -> $targetGenome")
          None
      }
    }

    // Resolve contig IDs for lifted positions
    val targetContigNames = liftoverResults.map(_._2).distinct

    genbankContigRepository.findByCommonNames(targetContigNames).map { contigs =>
      // Map: (CommonName, Genome) -> ContigID
      val contigMap = contigs.flatMap { c =>
        for {
          cn <- c.commonName
          rg <- c.referenceGenome
        } yield (cn, rg) -> c.id.get
      }.toMap

      liftoverResults.flatMap { case (targetGenome, liftedContig, liftedPos) =>
        // Try to find contig ID, handling chr prefix differences
        val contigId = contigMap.get((liftedContig, targetGenome))
          .orElse(contigMap.get((liftedContig.stripPrefix("chr"), targetGenome)))
          .orElse(contigMap.get(("chr" + liftedContig, targetGenome)))

        contigId.map { cid =>
          sourceVariant.copy(
            variantId = None,
            genbankContigId = cid,
            position = liftedPos
          )
        }
      }
    }
  }
}
