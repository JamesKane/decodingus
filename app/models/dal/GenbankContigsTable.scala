package models.dal

import models.GenbankContig
import slick.jdbc.PostgresProfile.api._

class GenbankContigsTable(tag: Tag) extends Table[GenbankContig](tag, "genbank_contig") {
  def genbankContigId = column[Int]("genbank_contig_id", O.PrimaryKey, O.AutoInc)
  def accession = column[String]("accession", O.Unique)
  def commonName = column[Option[String]]("common_name")
  def referenceGenome = column[Option[String]]("reference_genome")
  def seqLength = column[Int]("seq_length")

  def * = (genbankContigId.?, accession, commonName, referenceGenome, seqLength).mapTo[GenbankContig]
}
