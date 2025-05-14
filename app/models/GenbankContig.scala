package models

case class GenbankContig(
                          genbankContigId: Option[Int] = None,
                          accession: String,
                          commonName: Option[String],
                          referenceGenome: Option[String],
                          seqLength: Int
                        )