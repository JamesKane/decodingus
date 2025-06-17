package repositories

import models.domain.genomics.{MinHashSketch, SequenceLibrary, SequenceLibrarySketch}

import java.util.UUID
import scala.concurrent.Future

enum SketchType {
  case Autosomal, YChromosome, MtDna
}


trait SequenceLibrarySketchRepository {
  /**
   * Creates or updates a sketch for a sequence library
   */
  def upsert(sketch: SequenceLibrarySketch): Future[SequenceLibrarySketch]

  /**
   * Creates or updates multiple sketches in batch
   */
  def upsertBatch(sketches: Seq[SequenceLibrarySketch]): Future[Seq[SequenceLibrarySketch]]

  /**
   * Finds sequence libraries with similar autosomal sketches
   */
  def findSimilarAutosomal(sketchHash: String, threshold: Double): Future[Seq[SequenceLibrary]]

  /**
   * Finds sequence libraries with matching Y chromosome sketches
   */
  def findMatchingYChromosome(sketchHash: String): Future[Seq[SequenceLibrary]]

  /**
   * Finds sequence libraries with matching mtDNA sketches
   */
  def findMatchingMtDna(sketchHash: String): Future[Seq[SequenceLibrary]]

  /**
   * Retrieves the sketch for a specific sequence library
   */
  def findBySequenceLibraryId(sequenceLibraryId: Int): Future[Option[SequenceLibrarySketch]]

  /**
   * Finds all sketches for a given sample GUID
   */
  def findBySampleGuid(sampleGuid: UUID): Future[Seq[SequenceLibrarySketch]]

  /**
   * Finds sequence libraries with similar autosomal sketches using actual Jaccard similarity
   */
  def findSimilarAutosomal(
                            sketch: MinHashSketch,
                            threshold: Double
                          ): Future[Seq[(SequenceLibrary, Double)]] // Returns libraries with their similarity scores

  /**
   * Validates consistency between sequence libraries claiming to be from the same sample
   * Now using full MinHash comparison for accurate Jaccard similarities
   */
  def validateSampleConsistency(
                                 sampleGuid: UUID,
                                 autosomalThreshold: Double
                               ): Future[Seq[(SequenceLibrary, MinHashSketch, Double)]]

  /**
   * Batch comparison of sketches across a set of libraries
   * Useful for finding all pairs of similar libraries in a dataset
   */
  def findAllSimilarPairs(
                           threshold: Double,
                           sketchType: SketchType = SketchType.Autosomal
                         ): Future[Seq[(SequenceLibrary, SequenceLibrary, Double)]]

}
