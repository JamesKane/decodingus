package models.domain.genomics

/**
 * Represents a GenBank contig, a segment of DNA or RNA sequence, with associated information such as 
 * accession number, common name, reference genome, and sequence length.
 *
 * @param id              An optional unique identifier for the GenBank contig. Typically used for internal purposes.
 * @param accession       The accession number of the contig, providing a unique reference to this sequence in an external database.
 * @param commonName      An optional common name assigned to the contig for easier identification.
 * @param referenceGenome An optional reference genome name or identifier associated with the contig.
 * @param seqLength       The length of the DNA or RNA sequence represented by this contig.
 */
case class GenbankContig(
                          id: Option[Int] = None,
                          accession: String,
                          commonName: Option[String],
                          referenceGenome: Option[String],
                          seqLength: Int,
                          //pangenomePathId: Option[Int] = None
                        )