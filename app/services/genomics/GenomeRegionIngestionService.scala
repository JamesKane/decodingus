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

  private def ingestUrl(url: String, regionType: String, liftovers: Map[String, LiftOver]): Future[Unit] = {
    logger.info(s"Ingesting $regionType from $url")

    val regions = Future {
      Using.resource(Source.fromURL(url)) { source =>
        source.getLines()
          .filterNot(_.startsWith("#"))
          .filterNot(_.trim.isEmpty)
          .flatMap(line => parseBedLine(line, regionType, liftovers))
          .toSeq
      }
    }

    regions.flatMap { parsedRegions =>
      if (parsedRegions.nonEmpty) {
        val batches = parsedRegions.grouped(1000).toSeq
        batches.foldLeft(Future.successful(())) { (acc, batch) =>
          acc.flatMap(_ => repository.bulkCreateRegions(batch).map(_ => ()))
        }.map { _ =>
          logger.info(s"Ingested ${parsedRegions.size} regions for $regionType")
        }
      } else {
        logger.warn(s"No regions found for $regionType")
        Future.successful(())
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
