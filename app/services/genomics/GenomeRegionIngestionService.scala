package services.genomics

import config.GenomicsConfig
import htsjdk.samtools.liftover.LiftOver
import htsjdk.samtools.util.Interval
import jakarta.inject.{Inject, Singleton}
import models.domain.genomics.{GenomeRegion, RegionCoordinate}
import play.api.Logger
import play.api.libs.json.{JsObject, Json}
import repositories.GenomeRegionsRepository

import java.io.BufferedInputStream
import java.net.URL
import java.util.zip.GZIPInputStream
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.{Failure, Success, Using}

@Singleton
class GenomeRegionIngestionService @Inject()(
  repository: GenomeRegionsRepository,
  genomicsConfig: GenomicsConfig
)(implicit ec: ExecutionContext) {

  private val logger = Logger(this.getClass)

  // Source URLs (hs1 / CHM13v2.0)
  private val sources = Map(
    "Cytoband" -> "https://s3-us-west-2.amazonaws.com/human-pangenomics/T2T/CHM13/assemblies/annotation/chm13v2.0_cytobands_allchrs.bed",
    "CenSat" -> "https://s3-us-west-2.amazonaws.com/human-pangenomics/T2T/CHM13/assemblies/annotation/chm13v2.0_censat_v2.1.bed",
    "Telomere" -> "https://s3-us-west-2.amazonaws.com/human-pangenomics/T2T/CHM13/assemblies/annotation/chm13v2.0_telomere.bed",
    "SequenceClass" -> "https://s3-us-west-2.amazonaws.com/human-pangenomics/T2T/CHM13/assemblies/annotation/chm13v2.0_chrXY_sequence_class_v1.bed",
    "InvertedRepeat" -> "https://s3-us-west-2.amazonaws.com/human-pangenomics/T2T/CHM13/assemblies/annotation/chm13v2.0Y_inverted_repeats_v1.bed",
    "Amplicon" -> "https://s3-us-west-2.amazonaws.com/human-pangenomics/T2T/CHM13/assemblies/annotation/chm13v2.0Y_amplicons_v1.bed",
    "Y_Region" -> "https://s3-us-west-2.amazonaws.com/human-pangenomics/T2T/CHM13/assemblies/annotation/chm13v2.0Y_AZF_DYZ_v1.bed"
  )

  /**
   * Bootstraps the genome_region_v2 table from the configured URLs.
   */
  def bootstrap(): Future[Unit] = {
    logger.info("Starting Genome Region bootstrapping...")
    
    // Load liftovers for hs1 -> GRCh38 and hs1 -> GRCh37
    val liftovers = loadLiftovers("hs1", Seq("GRCh38", "GRCh37"))

    // Process each source sequentially
    val tasks = sources.map { case (regionType, url) =>
      () => ingestUrl(url, regionType, liftovers)
    }

    tasks.foldLeft(Future.successful(())) { (f, task) =>
      f.flatMap(_ => task())
    }.map { _ =>
      logger.info("Genome Region bootstrapping completed successfully.")
    }
  }

  private def ingestUrl(url: String, regionType: String, liftovers: Map[String, LiftOver]): Future[Unit] = Future {
    logger.info(s"Ingesting $regionType from $url")
    
    // Simple download and parse (streaming)
    Using.resource(Source.fromURL(url)) { source =>
      val regions = source.getLines()
        .filterNot(_.startsWith("#"))
        .filterNot(_.trim.isEmpty)
        .flatMap {
          case line =>
            parseBedLine(line, regionType, liftovers)
        }
        .toSeq

      if (regions.nonEmpty) {
        // Bulk insert
        // repository.bulkCreateRegions is async, so we need to wait or change this method to return Future
        // Since we are inside Future { ... }, we can block? No, better to group.
        // But repository returns Future.
        
        // Actually, let's just collect them all (memory might be an issue for massive files, but these BEDs are small)
        // Cytobands is small. CenSat is larger.
        // Let's create in batches of 1000
        regions.grouped(1000).foreach {
          case batch =>
            // Await to ensure sequential DB insertion (or we could use Future.sequence if we return Future)
            // Since we are wrapping the whole thing in Future {}, blocking here blocks that one thread.
            // Ideally we rewrite this to be fully async stream, but for "1 time bootstrap" this is acceptable.
            concurrent.Await.result(repository.bulkCreateRegions(batch), scala.concurrent.duration.Duration.Inf)
        }
        logger.info(s"Ingested ${regions.size} regions for $regionType")
      } else {
        logger.warn(s"No regions found for $regionType")
      }
    }
  }

  private def parseBedLine(line: String, regionType: String, liftovers: Map[String, LiftOver]): Option[GenomeRegion] = {
    val cols = line.split("\t")
    if (cols.length < 3) return None

    val contig = cols(0)
    // BED is 0-based start, 1-based exclusive end.
    // Our DB/HTSJDK Interval is 1-based inclusive.
    val start = cols(1).toLong + 1
    val end = cols(2).toLong
    
    val rawName = if (cols.length > 3) Some(cols(3)) else None
    
    // Uniqueness Fix: 
    // Cytoband names (p11.1) are repeated per chromosome -> chr1_p11.1
    // Repeats/Amplicons (IR3) can be repeated on same chromosome, and PAR1 starts at 0 on both X and Y.
    // Use ${contig}_${n}_${start} to ensure global uniqueness.
    val name = regionType match {
      case "Cytoband" => rawName.map(n => s"${contig}_$n")
      case "InvertedRepeat" | "Amplicon" | "Y_Region" | "CenSat" | "SequenceClass" => rawName.map(n => s"${contig}_${n}_$start")
      case _ => rawName
    }
    
    // Properties
    val props = regionType match {
      case "Cytoband" if cols.length > 4 => Json.obj("stain" -> cols(4))
      case "InvertedRepeat" if cols.length > 4 => Json.obj("score" -> cols(4)) // Just guessing useful fields
      case _ => Json.obj()
    }

    // HS1 Coordinate
    val hs1Coord = RegionCoordinate(contig, start, end)
    
    // Lift to targets
    val liftedCoords = liftovers.flatMap {
      case (targetGenome, liftOver) =>
        val interval = new Interval(contig, start.toInt, end.toInt)
        val lifted = liftOver.liftOver(interval)
        if (lifted != null) {
          Some(targetGenome -> RegionCoordinate(lifted.getContig, lifted.getStart, lifted.getEnd))
        } else None
    }

    val allCoords = liftedCoords + ("hs1" -> hs1Coord)

    Some(GenomeRegion(
      regionType = regionType,
      name = name,
      coordinates = allCoords,
      properties = props
    ))
  }

  private def loadLiftovers(source: String, targets: Seq[String]): Map[String, LiftOver] = {
    targets.flatMap {
      case target =>
        genomicsConfig.getLiftoverChainFile(source, target).flatMap {
          case file =>
            if (file.exists()) {
              logger.info(s"Loaded liftover chain for $source->$target: ${file.getPath}")
              Some(target -> new LiftOver(file))
            } else {
              logger.warn(s"Liftover chain $source->$target configured but not found at ${file.getPath}")
              None
            }
        }
    }.toMap
  }
}
