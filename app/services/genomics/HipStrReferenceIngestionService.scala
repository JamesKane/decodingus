package services.genomics

import config.GenomicsConfig
import htsjdk.samtools.liftover.LiftOver
import htsjdk.samtools.util.Interval
import jakarta.inject.{Inject, Singleton}
import models.domain.genomics.{MutationType, NamingStatus, StrCoordinates, VariantAliases, VariantV2}
import play.api.Logger
import play.api.libs.json.Json
import repositories.VariantV2Repository

import java.io.{BufferedInputStream, BufferedReader, File, FileOutputStream, InputStreamReader}
import java.net.{HttpURLConnection, URI}
import java.util.zip.GZIPInputStream
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

@Singleton
class HipStrReferenceIngestionService @Inject()(
  variantRepository: VariantV2Repository,
  genomicsConfig: GenomicsConfig
)(implicit ec: ExecutionContext) {

  private val logger = Logger(this.getClass)

  /**
   * Bootstraps STRs from the HipSTR reference catalog.
   */
  def bootstrap(): Future[Int] = {
    val targetFile = genomicsConfig.hipstrStoragePath
    
    downloadFile(genomicsConfig.hipstrUrl, targetFile).flatMap { _ =>
      ingestHipStrBed(targetFile)
    }
  }

  private def downloadFile(urlStr: String, targetFile: File): Future[Unit] = Future {
    // Cache check (24h)
    val cacheDuration = 24 * 60 * 60 * 1000L
    if (targetFile.exists() && (System.currentTimeMillis() - targetFile.lastModified() < cacheDuration)) {
      logger.info(s"Local HipSTR file is fresh (< 24 hours old), skipping download: ${targetFile.getAbsolutePath}")
    } else {
      val url = URI.create(urlStr).toURL
      logger.info(s"Downloading HipSTR reference from $url to ${targetFile.getAbsolutePath}")

      val parentDir = targetFile.getParentFile
      if (parentDir != null && !parentDir.exists()) parentDir.mkdirs()

      val tempFile = new File(targetFile.getAbsolutePath + ".tmp")
      val conn = url.openConnection().asInstanceOf[HttpURLConnection]
      conn.setConnectTimeout(30000)
      conn.setReadTimeout(300000)

      try {
        val in = new BufferedInputStream(conn.getInputStream)
        val out = new FileOutputStream(tempFile)
        val buffer = new Array[Byte](8192)
        Iterator.continually(in.read(buffer)).takeWhile(_ != -1).foreach(out.write(buffer, 0, _))
        in.close()
        out.close()
        
        if (targetFile.exists()) targetFile.delete()
        if (!tempFile.renameTo(targetFile)) throw new RuntimeException(s"Failed to rename $tempFile to $targetFile")
        
        logger.info(s"Downloaded HipSTR reference to ${targetFile.getAbsolutePath}")
      } finally {
        conn.disconnect()
      }
    }
  }

  private def ingestHipStrBed(file: File): Future[Int] = {
    logger.info(s"Starting HipSTR ingestion from ${file.getPath}")

    // Load liftovers: GRCh38 -> hs1, GRCh38 -> GRCh37
    val liftovers = loadLiftovers("GRCh38", Seq("hs1", "GRCh37"))
    
    // Using simple recursive batching to avoid blocking
    // GZIP reader
    val reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(new java.io.FileInputStream(file))))
    
    // Iterator for lines
    val iterator = Iterator.continually(reader.readLine()).takeWhile(_ != null).filterNot(_.startsWith("#"))
    val batchSize = 100

    def processNextBatch(count: Int): Future[Int] = {
      val batchLines = iterator.take(batchSize).toSeq
      
      if (batchLines.isEmpty) {
        reader.close()
        logger.info(s"HipSTR ingestion complete. Processed $count variants.")
        Future.successful(count)
      } else {
        val variants = batchLines.flatMap(line => parseHipStrLine(line, liftovers))
        
        variantRepository.upsertBatch(variants).flatMap { _ =>
          val newCount = count + batchLines.size // Count lines processed
          if (newCount % 1000 == 0) logger.info(s"Processed $newCount HipSTR records...")
          processNextBatch(newCount)
        }
      }
    }

    processNextBatch(0).transform {
      result =>
        Try(reader.close())
        result
    }
  }

  private def parseHipStrLine(line: String, liftovers: Map[String, LiftOver]): Option[VariantV2] = {
    // BED columns: chrom, start, end, period, ref_repeats, id, motif, [structure]
    val cols = line.split("\t")
    if (cols.length < 6) return None

    val rawContig = cols(0)
    val contig = if (rawContig.matches("^[0-9XY]+$")) s"chr$rawContig" else rawContig
    val start = cols(1).toLong + 1 // BED 0-based -> 1-based inclusive
    val end = cols(2).toLong
    val period = cols(3).toInt
    val refRepeats = cols(4).toDouble.toInt // Sometimes float?
    val name = cols(5)
    val motif = if (cols.length > 6) Some(cols(6)) else None

    // Source coordinates (GRCh38)
    val grch38Coords = Json.toJson(StrCoordinates(
      contig = contig,
      start = start,
      end = end,
      period = period,
      repeatMotif = motif,
      referenceRepeats = Some(refRepeats)
    ))

    // Lift over
    val liftedCoords = liftovers.flatMap {
      case (targetGenome, liftOver) =>
        val interval = new Interval(contig, start.toInt, end.toInt)
        val lifted = liftOver.liftOver(interval)
        if (lifted != null) {
          Some(targetGenome -> Json.toJson(StrCoordinates(
            contig = lifted.getContig,
            start = lifted.getStart,
            end = lifted.getEnd,
            period = period,
            repeatMotif = motif,
            referenceRepeats = Some(refRepeats) // Approximation, technically assumes reference length similarity
          )))
        } else None
    }

    val allCoords = liftedCoords + ("GRCh38" -> grch38Coords)
    val combinedCoordsJson = allCoords.foldLeft(Json.obj()) { case (acc, (k, v)) => acc + (k -> v) }

    val aliases = Json.toJson(VariantAliases(
      commonNames = Seq(name),
      sources = Map("HipSTR" -> Seq(name))
    ))

    Some(VariantV2(
      canonicalName = Some(name),
      mutationType = MutationType.STR,
      namingStatus = NamingStatus.Named,
      aliases = aliases,
      coordinates = combinedCoordsJson,
      notes = Some("Imported from HipSTR catalog")
    ))
  }

  private def loadLiftovers(source: String, targets: Seq[String]): Map[String, LiftOver] = {
    targets.flatMap {
      target =>
        genomicsConfig.getLiftoverChainFile(source, target).flatMap {
          file =>
            if (file.exists()) {
              logger.info(s"Loaded liftover chain for $source->$target")
              Some(target -> new LiftOver(file))
            } else None
        }
    }.toMap
  }
}
