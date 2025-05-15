package models.dal

import models.QualityMetrics
import slick.jdbc.PostgresProfile.api.*

class QualityMetricsTable(tag: Tag) extends Table[QualityMetrics](tag, "quality_metrics") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def contigId = column[Int]("contig_id")
  def startPosition = column[Int]("start_pos")
  def endPosition = column[Int]("end_pos")
  def numReads = column[Int]("num_reads")
  def refN = column[Int]("ref_n")
  def noCov = column[Int]("no_cov")
  def lowCov = column[Int]("low_cov")
  def excessiveCov = column[Int]("excessive_cov")
  def poorMQ = column[Int]("poor_mq")
  def callable = column[Int]("callable")
  def covPercent = column[Double]("cov_percent")
  def meanDepth = column[Double]("mean_depth")
  def meanMQ = column[Double]("mean_mq")
  def sequenceFileId = column[Int]("sequence_file_id")
  
  def * = (id.?, contigId, startPosition, endPosition, numReads, refN, noCov, lowCov, excessiveCov, poorMQ, callable, covPercent, meanDepth, meanMQ, sequenceFileId).mapTo[QualityMetrics]
}
