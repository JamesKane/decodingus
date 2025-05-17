package models

/**
 * Represents a genetic variant with detailed information about its genomic location, reference allele,
 * alternate allele, variant type, and optional metadata such as identifiers and common names.
 *
 * @param variantId       An optional unique identifier for the variant, used internally for tracking purposes.
 * @param genbankContigId The unique identifier for the genomic contig in GenBank where this variant is located.
 * @param position        The position of the variant on the genomic contig.
 * @param referenceAllele The reference allele at the specific genomic position.
 * @param alternateAllele The alternate allele representing the variant at the specific position.
 * @param variantType     The type of the variant (e.g., SNP, insertion, deletion).
 * @param rsId            An optional rs identifier (dbSNP ID) associated with this variant, if available.
 * @param commonName      An optional common name or description for the variant.
 */
case class Variant(
                    variantId: Option[Int] = None,
                    genbankContigId: Int,
                    position: Int,
                    referenceAllele: String,
                    alternateAllele: String,
                    variantType: String,
                    rsId: Option[String],
                    commonName: Option[String]
                  )
