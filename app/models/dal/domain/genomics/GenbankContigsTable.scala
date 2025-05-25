package models.dal.domain.genomics

import models.domain.GenbankContig
import slick.jdbc.PostgresProfile.api.*

/**
 * Represents the database table for GenBank contigs, mapping to the `GenbankContig` case class.
 *
 * This table stores information about DNA or RNA sequence segments, including their unique 
 * identifiers, accession numbers, optional common names, reference genomes, and sequence lengths.
 *
 * @constructor Creates a new instance of the `GenbankContigsTable` class.
 * @param tag A Slick `Tag` object used for scoping and referencing the table in the database schema.
 *
 *            Columns:
 *            - `genbankContigId`: Auto-incrementing primary key, uniquely identifying each contig.
 *            - `accession`: Unique accession number for the contig, serving as a reference to the external database.
 *            - `commonName`: Optional common name assigned to the contig for easier identification.
 *            - `referenceGenome`: Optional reference genome name or identifier associated with the contig.
 *            - `seqLength`: Length of the DNA or RNA sequence represented by the contig.
 *
 *            Primary key:
 *            - `genbankContigId`.
 *
 *            Mapping:
 *            - Maps to the `GenbankContig` case class using the corresponding columns.
 */
class GenbankContigsTable(tag: Tag) extends Table[GenbankContig](tag, "genbank_contig") {
  def genbankContigId = column[Int]("genbank_contig_id", O.PrimaryKey, O.AutoInc)

  def accession = column[String]("accession", O.Unique)

  def commonName = column[Option[String]]("common_name")

  def referenceGenome = column[Option[String]]("reference_genome")

  def seqLength = column[Int]("seq_length")

  def pangenomePathId = column[Option[Int]]("pangenome_path_id")

  def * = (genbankContigId.?, accession, commonName, referenceGenome, seqLength, pangenomePathId).mapTo[GenbankContig]
}
