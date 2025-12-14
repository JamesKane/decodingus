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
import scala.collection.AbstractIterator

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

    val batchSize = 100
    val source = Source.fromFile(gffFile)
    
    try {
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

      def processNextBatch(accumulatedCount: Int): Future[Int] = {
        // Synchronously take a batch from the iterator to avoid blocking the thread later
        // (Iterator access is fast, processing is slow)
        val batchGroups = scala.collection.mutable.ArrayBuffer[Seq[Map[String, String]]]()
        var taken = 0
        while (taken < batchSize && groupedIterator.hasNext) {
          batchGroups += groupedIterator.next()
          taken += 1
        }

        if (batchGroups.isEmpty) {
          logger.info(s"GFF ingestion complete. Total variants: $accumulatedCount")
          Future.successful(accumulatedCount)
        } else {
          // Process batch as a whole using optimized batch upsert
          val variantsToProcess = batchGroups.flatMap(group => createVariantV2FromGffGroup(group, sourceGenome, liftovers)).toSeq
          
          variantV2Repository.upsertBatch(variantsToProcess).flatMap { resultIds =>
            val batchCount = resultIds.size
            val newTotal = accumulatedCount + batchCount
/*            if (newTotal % 1000 == 0) { // Log every 1000 now that it should be faster
               logger.info(s"Processed $newTotal variants from GFF...")
            }*/
            processNextBatch(newTotal)
          }
        }
      }

      processNextBatch(0).andThen { case _ => 
        source.close() 
      }
    } catch {
      case e: Exception =>
        source.close()
        Future.failed(e)
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
    val ref = primary.getOrElse("allele_anc", primary.getOrElse("ref_allele", primary.getOrElse("reference_allele", "")))
    val alt = primary.getOrElse("allele_der", primary.getOrElse("alt_allele", primary.getOrElse("derived_allele", "")))
    
    if (ref.isEmpty || alt.isEmpty) {
      if (Math.random() < 0.001) logger.warn(s"Missing alleles for GFF record (sampling): $primary")
      return None // Skip if alleles missing
    }

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
    val rawPlacements = Json.obj(
      "ycc" -> primary.get("ycc_haplogroup"),
      "isogg" -> primary.get("isogg_haplogroup"),
      "yfull" -> primary.get("yfull_node") // User clarified this is a haplogroup placement
    )
    
    val placements = JsObject(rawPlacements.fields.filterNot { case (_, v) =>
      v match {
        case play.api.libs.json.JsString(s) => s == "." || s == "not listed" || s == "unknown"
        case _ => false
      }
    })

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
