package models

case class GenbankContig(
                          id: Option[Int] = None,
                          accession: String,
                          commonName: Option[String],
                          referenceGenome: Option[String],
                          seqLength: Int
                        )