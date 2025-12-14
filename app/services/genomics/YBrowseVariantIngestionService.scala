package services.genomics

import config.GenomicsConfig
import htsjdk.samtools.liftover.LiftOver
import htsjdk.samtools.reference.{ReferenceSequenceFile, ReferenceSequenceFileFactory}
import htsjdk.samtools.util.Interval
import htsjdk.variant.variantcontext.VariantContext
import htsjdk.variant.vcf.VCFFileReader
import jakarta.inject.{Inject, Singleton}
import models.dal.domain.genomics.*
import models.domain.genomics.{MutationType, NamingStatus, VariantAliases, VariantV2}
import play.api.Logger
import play.api.libs.json.{JsObject, Json}
import repositories.VariantV2Repository

import java.io.File
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.io.Source
import scala.util.{Failure, Success, Try, Using}

/**
 * Service for ingesting Y-DNA variants from YBrowse VCF and GFF files.
 *
 * Creates consolidated VariantV2 records with JSONB coordinates for multiple
 * reference genomes. Performs liftover to add coordinates for additional
 * assemblies (hs1, GRCh37, etc.).
 */
@Singleton
class YBrowseVariantIngestionService @Inject()(
  variantV2Repository: VariantV2Repository,
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
   * Ingests variants from a YBrowse GFF3 file.
   * Groups adjacent records with same coordinates to handle aliases.
   *
   * @param gffFile      The GFF3 file to ingest.
   * @param sourceGenome The reference genome of the input GFF (default: "GRCh38").
   * @return A Future containing the number of variants ingested.
   */
  def ingestGff(gffFile: File, sourceGenome: String = "GRCh38"): Future[Int] = {
    logger.info(s"Starting GFF ingestion from ${gffFile.getPath} ($sourceGenome)")
    
    val canonicalSource = genomicsConfig.resolveReferenceName(sourceGenome)
    val targetGenomes = genomicsConfig.supportedReferences.filter(_ != canonicalSource)
    
    // Load liftovers
    val liftovers: Map[String, LiftOver] = targetGenomes.flatMap { target =>
      genomicsConfig.getLiftoverChainFile(canonicalSource, target).flatMap { file =>
        if (file.exists()) Some(target -> new LiftOver(file)) else None
      }
    }.toMap

    val batchSize = 1000
    
    // Process file in chunks
    // Grouping by coordinates requires a buffered approach since lines are sequential
    Future {
      Using.resource(Source.fromFile(gffFile)) { source =>
        val lines = source.getLines().filterNot(_.startsWith("#"))
        
        // Custom grouping iterator that groups adjacent lines with same Chr/Pos/Ref/Alt
        val groupedIterator = new AbstractIterator[Seq[Map[String, String]]] {
          private var buffer: Option[Map[String, String]] = None
          
          override def hasNext: Boolean = buffer.isDefined || lines.hasNext
          
          override def next(): Seq[Map[String, String]] = {
            if (!hasNext) throw new NoSuchElementException("next on empty iterator")
            
            val currentGroup = scala.collection.mutable.ArrayBuffer[Map[String, String]]()
            
            // Initialize with buffer or next line
            val first = buffer.getOrElse(parseGffLine(lines.next()))
            buffer = None // Clear buffer
            
            if (first.isEmpty) return next() // Skip malformed/empty lines
            
            currentGroup += first
            
            // Key to identify the group (Chr, Start, End)
            // Note: We use Start/End as proxy for position. Ref/Alt are in attributes, harder to peek.
            // GFF for SNPs usually has Start == End.
            val groupKey = (first("seqid"), first("start"), first("end"))
            
            // Peek ahead
            var keepingGoing = true
            while (keepingGoing && lines.hasNext) {
              val nextLine = parseGffLine(lines.next())
              if (nextLine.nonEmpty) {
                val nextKey = (nextLine("seqid"), nextLine("start"), nextLine("end"))
                if (nextKey == groupKey) {
                  currentGroup += nextLine
                } else {
                  buffer = Some(nextLine)
                  keepingGoing = false
                }
              }
            }
            
            currentGroup.toSeq
          }
        }

        // Process groups in batches
        var count = 0
        var batch = scala.collection.mutable.ArrayBuffer[VariantV2]()
        
        groupedIterator.foreach { group =>
          createVariantV2FromGffGroup(group, sourceGenome, liftovers).foreach(batch += _)
          
          if (batch.size >= batchSize) {
            // Process batch sequentially with smart upsert
            // Note: Await is used here to keep the ingestion flow simple and backpressure the file reading
            val futures = batch.map(smartUpsertVariant)
            val batchCount = concurrent.Await.result(Future.sequence(futures), scala.concurrent.duration.Duration.Inf).sum
            count += batchCount
            logger.info(s"Processed $count variants from GFF...")
            batch.clear()
          }
        }
        
        // Final batch
        if (batch.nonEmpty) {
          val futures = batch.map(smartUpsertVariant)
          val batchCount = concurrent.Await.result(Future.sequence(futures), scala.concurrent.duration.Duration.Inf).sum
          count += batchCount
        }
        
        logger.info(s"GFF ingestion complete. Total variants: $count")
        count
      }
    }
  }

  /**
   * Smart Upsert: Matches existing variant by Coordinates OR Name/Alias.
   * Merges data if found, Creates if not.
   */
  private def smartUpsertVariant(incoming: VariantV2): Future[Int] = {
    variantV2Repository.findMatches(incoming).flatMap { matches =>
      if (matches.isEmpty) {
        // No match -> Create
        variantV2Repository.create(incoming)
      } else {
        // Match found -> Merge and Update
        // If multiple matches, we pick the first one (merging duplicates is a separate maintenance task)
        val existing = matches.head
        
        // 1. Merge Aliases
        val mergedAliases = mergeAliases(existing.aliases, incoming.aliases)
        
        // 2. Merge Coordinates
        val mergedCoordinates = (existing.coordinates.as[JsObject] ++ incoming.coordinates.as[JsObject])
        
        // 3. Merge Evidence
        val mergedEvidence = (existing.evidence.as[JsObject] ++ incoming.evidence.as[JsObject])
        
        // 4. Merge Primers
        val mergedPrimers = (existing.primers.as[JsObject] ++ incoming.primers.as[JsObject])
        
        // 5. Update Canonical Name (if existing is unnamed and incoming is named)
        val (newCanonical, newStatus) = if (existing.namingStatus == NamingStatus.Unnamed && incoming.namingStatus == NamingStatus.Named) {
          (incoming.canonicalName, NamingStatus.Named)
        } else {
          (existing.canonicalName, existing.namingStatus)
        }
        
        val updated = existing.copy(
          canonicalName = newCanonical,
          namingStatus = newStatus,
          aliases = mergedAliases,
          coordinates = mergedCoordinates,
          evidence = mergedEvidence,
          primers = mergedPrimers,
          notes = existing.notes.orElse(incoming.notes),
          updatedAt = java.time.Instant.now()
        )
        
        variantV2Repository.update(updated).map(_ => 1) // Return 1 to count as "processed"
      }
    }
  }

  private def mergeAliases(existing: play.api.libs.json.JsValue, incoming: play.api.libs.json.JsValue): play.api.libs.json.JsValue = {
    import play.api.libs.json.*
    
    val eCommon = (existing \ "common_names").asOpt[Seq[String]].getOrElse(Seq.empty)
    val iCommon = (incoming \ "common_names").asOpt[Seq[String]].getOrElse(Seq.empty)
    val mergedCommon = (eCommon ++ iCommon).distinct
    
    val eRs = (existing \ "rs_ids").asOpt[Seq[String]].getOrElse(Seq.empty)
    val iRs = (incoming \ "rs_ids").asOpt[Seq[String]].getOrElse(Seq.empty)
    val mergedRs = (eRs ++ iRs).distinct
    
    // Deep merge sources is harder, simple merge for now
    val eSources = (existing \ "sources").asOpt[JsObject].getOrElse(Json.obj())
    val iSources = (incoming \ "sources").asOpt[JsObject].getOrElse(Json.obj())
    // For source arrays, we really should merge the arrays, but standard ++ overwrites keys.
    // A robust merge would iterate keys.
    // Let's do a slightly better merge for sources
    val mergedSources = iSources.fields.foldLeft(eSources) { case (acc, (key, newVal)) =>
      val oldVal = (acc \ key).asOpt[Seq[String]].getOrElse(Seq.empty)
      val nextVal = newVal.asOpt[Seq[String]].getOrElse(Seq.empty)
      acc + (key -> Json.toJson((oldVal ++ nextVal).distinct))
    }
    
    Json.obj(
      "common_names" -> mergedCommon,
      "rs_ids" -> mergedRs,
      "sources" -> mergedSources
    )
  }

  private def parseGffLine(line: String): Map[String, String] = {
    val cols = line.split("\t")
    if (cols.length < 9) return Map.empty
    
    val attributes = cols(8).split(";").map { kv =>
      val parts = kv.split("=", 2)
      if (parts.length == 2) parts(0) -> parts(1) else "" -> ""
    }.toMap.filter(_._1.nonEmpty)
    
    Map(
      "seqid" -> cols(0),
      "source" -> cols(1),
      "type" -> cols(2),
      "start" -> cols(3),
      "end" -> cols(4),
      "score" -> cols(5),
      "strand" -> cols(6),
      "phase" -> cols(7)
    ) ++ attributes
  }

  private def createVariantV2FromGffGroup(
    group: Seq[Map[String, String]], 
    sourceGenome: String,
    liftovers: Map[String, LiftOver]
  ): Option[VariantV2] = {
    // First record determines canonical info
    val primary = group.head
    val name = primary.getOrElse("Name", primary.getOrElse("ID", "Unknown"))
    
    // Parse coordinates
    val contig = primary("seqid")
    val start = primary("start").toInt
    // GFF attributes for alleles
    val ref = primary.getOrElse("allele_anc", "")
    val alt = primary.getOrElse("allele_der", "")
    
    if (ref.isEmpty || alt.isEmpty) return None // Skip if alleles missing

    // Normalize
    val refSeq = referenceFastaFiles.get(sourceGenome)
    val (normPos, normRef, normAlt) = normalizeVariant(
      contig, start, ref, alt, refSeq
    )

    // Build coordinates JSONB
    val sourceCoords = Json.obj(
      "contig" -> contig,
      "position" -> normPos,
      "ref" -> normRef,
      "alt" -> normAlt
    )

    // Lift over
    val liftedCoords = liftovers.flatMap { case (targetGenome, liftOver) =>
      val interval = new Interval(contig, start, primary("end").toInt)
      val lifted = liftOver.liftOver(interval)
      
      if (lifted != null) {
        val targetRefSeq = referenceFastaFiles.get(targetGenome)
        // Note: We use original ref/alt for normalization on target, 
        // assuming alleles translate directly (which is true for homology map).
        // A more robust way would be to fetch ref from target fasta.
        val (lPos, lRef, lAlt) = normalizeVariant(
          lifted.getContig, lifted.getStart, ref, alt, targetRefSeq
        )
        
        Some(targetGenome -> Json.obj(
          "contig" -> lifted.getContig,
          "position" -> lPos,
          "ref" -> lRef,
          "alt" -> lAlt
        ))
      } else None
    }

    val allCoordinates = (liftedCoords + (sourceGenome -> sourceCoords)).foldLeft(Json.obj()) {
      case (acc, (genome, coords)) => acc + (genome -> coords)
    }

    // Collect Metadata
    val commonNames = group.flatMap(_.get("Name")).distinct
    val rsIds = group.flatMap(_.get("Name")).filter(_.startsWith("rs")).distinct // Naive check
    val ybrowseIds = group.flatMap(_.get("ID")).distinct
    
    // Sources map: source -> [names]
    // Use 'ref' attribute from GFF as source attribution
    val sourceMap = group.groupBy(_.getOrElse("ref", "ybrowse")).map { case (src, records) =>
      src -> records.flatMap(_.get("Name")).distinct
    }

    val aliases = Json.obj(
      "common_names" -> commonNames,
      "rs_ids" -> rsIds,
      "sources" -> (Json.toJsObject(sourceMap) + ("ybrowse_id" -> Json.toJson(ybrowseIds)))
    )

    // Evidence
    val tested = primary.get("count_tested").map(_.toInt).getOrElse(0)
    val derived = primary.get("count_derived").map(_.toInt).getOrElse(0)
    
    // External Placements (Haplogroups)
    val placements = Json.obj(
      "ycc" -> primary.get("ycc_haplogroup"),
      "isogg" -> primary.get("isogg_haplogroup"),
      "yfull" -> primary.get("yfull_node") // User clarified this is a haplogroup placement
    ).filterNot(_._2.exists(v => v == "." || v == "not listed" || v == "unknown"))

    val evidence = Json.obj(
      "yseq_tested" -> tested,
      "yseq_derived" -> derived,
      "external_placements" -> placements
    )

    // Primers
    val primers = if (primary.contains("primer_f")) {
      Json.obj(
        "yseq_f" -> primary.getOrElse("primer_f", ""),
        "yseq_r" -> primary.getOrElse("primer_r", "")
      )
    } else Json.obj()

    // Notes
    val notes = primary.get("comment").filter(_ != ".")

    Some(VariantV2(
      canonicalName = Some(name),
      mutationType = MutationType.SNP, // GFF type 'point'/'snp' usually implies SNP
      namingStatus = NamingStatus.Named,
      aliases = aliases,
      coordinates = allCoordinates,
      evidence = evidence,
      primers = primers,
      notes = notes
    ))
  }

  /**
   * Ingests variants from a YBrowse VCF file.
   *
   * @param vcfFile      The VCF file to ingest.
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

  private def processBatch(
    batch: Seq[VariantContext],
    liftovers: Map[String, LiftOver],
    sourceGenome: String
  ): Future[Int] = {
    // Convert each VariantContext to a VariantV2 with JSONB coordinates
    val variantsV2 = batch.flatMap { vc =>
      createVariantV2FromContext(vc, sourceGenome, liftovers)
    }

    if (variantsV2.isEmpty && batch.nonEmpty) {
      logger.warn(s"No variants created from batch of ${batch.size} records!")
      Future.successful(0)
    } else {
      // Use findOrCreate for each variant (coordinates in JSONB)
      variantV2Repository.findOrCreateBatch(variantsV2).map(_.size)
    }
  }

  /**
   * Creates a VariantV2 from a VariantContext, including lifted coordinates.
   *
   * This consolidates what was previously N rows (one per reference) into
   * a single VariantV2 with JSONB coordinates containing all assemblies.
   */
  private def createVariantV2FromContext(
    vc: VariantContext,
    sourceGenome: String,
    liftovers: Map[String, LiftOver]
  ): Seq[VariantV2] = {
    // Handle multi-allelic variants - create one VariantV2 per alternate allele
    vc.getAlternateAlleles.asScala.map { alt =>
      // Parse variant identity from VCF ID column
      val rawId = Option(vc.getID).filterNot(id => id == "." || id.isEmpty)
      val rsId = rawId.filter(_.toLowerCase.startsWith("rs"))
      // For Y-DNA, the ID column often contains the SNP name (e.g. M269)
      val commonName = rawId

      // Normalize the variant for source genome
      val refSeq = referenceFastaFiles.get(sourceGenome)
      val (normPos, normRef, normAlt) = normalizeVariant(
        vc.getContig,
        vc.getStart,
        vc.getReference.getDisplayString,
        alt.getDisplayString,
        refSeq
      )

      // Build source genome coordinates
      val sourceCoords = Json.obj(
        "contig" -> vc.getContig,
        "position" -> normPos,
        "ref" -> normRef,
        "alt" -> normAlt
      )

      // Perform liftover to other reference genomes
      val liftedCoords = liftovers.flatMap { case (targetGenome, liftOver) =>
        val interval = new Interval(vc.getContig, vc.getStart, vc.getEnd)
        val lifted = liftOver.liftOver(interval)

        if (lifted != null) {
          // Normalize for target genome
          val targetRefSeq = referenceFastaFiles.get(targetGenome)
          val (liftedPos, liftedRef, liftedAlt) = normalizeVariant(
            lifted.getContig,
            lifted.getStart,
            vc.getReference.getDisplayString,
            alt.getDisplayString,
            targetRefSeq
          )

          Some(targetGenome -> Json.obj(
            "contig" -> lifted.getContig,
            "position" -> liftedPos,
            "ref" -> liftedRef,
            "alt" -> liftedAlt
          ))
        } else {
          None
        }
      }

      // Combine all coordinates into JSONB
      val allCoordinates = (liftedCoords + (sourceGenome -> sourceCoords)).foldLeft(Json.obj()) {
        case (acc, (genome, coords)) => acc + (genome -> coords)
      }

      // Build aliases JSONB
      val commonNames = commonName.toSeq.flatMap(_.split(",").map(_.trim).filter(_.nonEmpty))
      val aliasesJson = Json.obj(
        "common_names" -> commonNames,
        "rs_ids" -> rsId.toSeq,
        "sources" -> Json.obj(
          "ybrowse" -> commonNames
        )
      )

      // Determine mutation type
      val mutationType = vc.getType.toString match {
        case "SNP" => MutationType.SNP
        case "INDEL" | "MIXED" => MutationType.INDEL
        case "MNP" => MutationType.MNP
        case other =>
          logger.debug(s"Unknown variant type: $other, defaulting to SNP")
          MutationType.SNP
      }

      VariantV2(
        canonicalName = commonName.map(_.split(",").head.trim),
        mutationType = mutationType,
        namingStatus = if (commonName.isDefined) NamingStatus.Named else NamingStatus.Unnamed,
        aliases = aliasesJson,
        coordinates = allCoordinates
      )
    }.toSeq
  }

  /**
   * Normalizes a variant by performing VCF-style left-alignment.
   *
   * The algorithm:
   * 1. Right-trim: Remove common suffix bases from ref and alt alleles
   * 2. Pad: If either allele becomes empty, prepend the preceding reference base
   * 3. Left-trim: Remove common prefix bases (keeping at least 1 base on each)
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
   * Lifts a variant to all other supported reference genomes and adds coordinates.
   *
   * @param variantId    The variant to update with additional coordinates
   * @param sourceGenome The source reference genome
   * @return Future containing the number of coordinates added
   */
  def addLiftedCoordinates(variantId: Int, sourceGenome: String): Future[Int] = {
    variantV2Repository.findById(variantId).flatMap {
      case Some(variant) =>
        val sourceCoords = variant.getCoordinates(sourceGenome)
        sourceCoords match {
          case Some(coords) =>
            val contig = (coords \ "contig").asOpt[String].getOrElse("")
            val position = (coords \ "position").asOpt[Int].getOrElse(0)
            val ref = (coords \ "ref").asOpt[String].getOrElse("")
            val alt = (coords \ "alt").asOpt[String].getOrElse("")

            val canonicalSource = genomicsConfig.resolveReferenceName(sourceGenome)
            val targetGenomes = genomicsConfig.supportedReferences.filter(_ != canonicalSource)

            val liftedFutures = targetGenomes.flatMap { targetGenome =>
              genomicsConfig.getLiftoverChainFile(canonicalSource, targetGenome) match {
                case Some(chainFile) if chainFile.exists() =>
                  val liftOver = new LiftOver(chainFile)
                  val interval = new Interval(contig, position, position)
                  val lifted = liftOver.liftOver(interval)

                  if (lifted != null) {
                    val liftedCoords = Json.obj(
                      "contig" -> lifted.getContig,
                      "position" -> lifted.getStart,
                      "ref" -> ref,
                      "alt" -> alt
                    )
                    Some(variantV2Repository.addCoordinates(variantId, targetGenome, liftedCoords))
                  } else {
                    None
                  }
                case _ => None
              }
            }

            Future.sequence(liftedFutures).map(_.count(_ == true))

          case None =>
            logger.warn(s"Variant $variantId has no coordinates for $sourceGenome")
            Future.successful(0)
        }

      case None =>
        logger.warn(s"Variant $variantId not found")
        Future.successful(0)
    }
  }
}
