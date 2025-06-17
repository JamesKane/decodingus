package models.domain.genomics

import play.api.libs.json.{Format, Json}


import java.nio.ByteBuffer
import java.time.LocalDateTime

case class MinHashSketch(
                          kmerHashes: Array[Long], // The actual MinHash values
                          finalHash: String // SHA256 of the sorted kmerHashes for quick identity checks
                        )

object MinHashSketch {
  def computeJaccard(sketch1: MinHashSketch, sketch2: MinHashSketch): Double = {
    val set1 = sketch1.kmerHashes.toSet
    val set2 = sketch2.kmerHashes.toSet
    val intersection = set1.intersect(set2).size
    val union = set1.union(set2).size
    intersection.toDouble / union
  }
}

  case class SequenceLibrarySketch(
                                  id: Option[Int] = None,
                                  sequenceLibraryId: Int,
                                  autosomalSketch: MinHashSketch,
                                  yChromosomeSketch: Option[MinHashSketch],
                                  mtDnaSketch: Option[MinHashSketch],
                                  createdAt: LocalDateTime = LocalDateTime.now()
                                )

