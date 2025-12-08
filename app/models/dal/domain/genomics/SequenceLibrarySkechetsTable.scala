package models.dal.domain.genomics

import models.dal.MyPostgresProfile.api.*
import models.domain.genomics.{MinHashSketch, SequenceLibrarySketch}

import java.nio.ByteBuffer
import java.time.LocalDateTime


class SequenceLibrarySketchesTable(tag: Tag) extends Table[SequenceLibrarySketch](tag, "sequence_library_sketch") {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)

  def sequenceLibraryId = column[Int]("sequence_library_id")

  def autosomalKmerHashes = column[Array[Byte]]("autosomal_kmers", O.SqlType("bytea"))

  def autosomalHash = column[String]("autosomal_hash")

  def yChromosomeKmerHashes = column[Option[Array[Byte]]]("y_chromosome_kmers", O.SqlType("bytea"))

  def yChromosomeHash = column[Option[String]]("y_chromosome_hash")

  def mtDnaKmerHashes = column[Option[Array[Byte]]]("mt_dna_kmers", O.SqlType("bytea"))

  def mtDnaHash = column[Option[String]]("mt_dna_hash")

  def createdAt = column[LocalDateTime]("created_at")

  // Use proper constructor syntax for case class
  private def toMinHashSketch(bytes: Array[Byte], hash: String): MinHashSketch =
    new MinHashSketch(MinHashSketch.bytesToLongArray(bytes), hash)

  private def toOptionalMinHashSketch(bytesAndHash: (Option[Array[Byte]], Option[String])): Option[MinHashSketch] =
    for {
      bytes <- bytesAndHash._1
      hash <- bytesAndHash._2
    } yield toMinHashSketch(bytes, hash)

  def * = (
    id.?,
    sequenceLibraryId,
    (autosomalKmerHashes, autosomalHash) <> ( {
      case (bytes: Array[Byte], hash: String) => toMinHashSketch(bytes, hash)
    }, { (sketch: MinHashSketch) =>
      Some((MinHashSketch.longArrayToBytes(sketch.kmerHashes), sketch.finalHash))
    }
    ),
    (yChromosomeKmerHashes, yChromosomeHash) <> ( { (tuple: (Option[Array[Byte]], Option[String])) => toOptionalMinHashSketch(tuple) }, { (optSketch: Option[MinHashSketch]) =>
      Some((
        optSketch.map(sketch => MinHashSketch.longArrayToBytes(sketch.kmerHashes)),
        optSketch.map(_.finalHash)
      ))
    }
    ),
    (mtDnaKmerHashes, mtDnaHash) <> ( { (tuple: (Option[Array[Byte]], Option[String])) => toOptionalMinHashSketch(tuple) }, { (optSketch: Option[MinHashSketch]) =>
      Some((
        optSketch.map(sketch => MinHashSketch.longArrayToBytes(sketch.kmerHashes)),
        optSketch.map(_.finalHash)
      ))
    }
    ),
    createdAt
  ) <> ((SequenceLibrarySketch.apply _).tupled, SequenceLibrarySketch.unapply)

  def sequenceLibrary = foreignKey(
    "fk_sequence_library_sketch_library",
    sequenceLibraryId,
    TableQuery[SequenceLibrariesTable])(_.id, onDelete = ForeignKeyAction.Cascade)
}


object MinHashSketch {
  def longArrayToBytes(arr: Array[Long]): Array[Byte] = {
    val bb = ByteBuffer.allocate(arr.length * 8)
    arr.foreach(bb.putLong)
    bb.array()
  }

  def bytesToLongArray(bytes: Array[Byte]): Array[Long] = {
    val bb = ByteBuffer.wrap(bytes)
    val result = new Array[Long](bytes.length / 8)
    for (i <- result.indices) {
      result(i) = bb.getLong()
    }
    result
  }


}
