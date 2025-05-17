package models

import java.time.LocalDateTime
import java.util.UUID

/**
 * Represents a reported genetic variant associated with a sample, including detailed metadata 
 * about its genomic properties, provenance, and reporting status.
 *
 * @param id              An optional unique identifier for the reported variant, typically used internally.
 * @param sampleGuid      A universally unique identifier (UUID) for the sample that the variant is associated with.
 * @param genbankContigId The identifier for the contig in the GenBank database where the variant is located. 
 * @param position        The genomic position of the variant on the contig.
 * @param referenceAllele The reference allele at the variant's position, representing the expected base(s) in the reference genome.
 * @param alternateAllele The alternate allele at the variant's position, representing the observed mutation or variation.
 * @param variantType     The type of the variant (e.g., SNP, insertion, deletion).
 * @param reportedDate    The timestamp indicating when the variant was reported or recorded.
 * @param provenance      A textual description or identifier providing information about the origin or source of the report.
 * @param confidenceScore A numerical score representing the level of confidence in the reported variant's data.
 * @param notes           Free-form text notes providing additional context or comments about the reported variant.
 * @param status          The reporting status of the variant (e.g., confirmed, pending, rejected).
 */
case class ReportedVariant(
                            id: Option[Long],
                            sampleGuid: UUID,
                            genbankContigId: Int,
                            position: Int,
                            referenceAllele: String,
                            alternateAllele: String,
                            variantType: String,
                            reportedDate: LocalDateTime,
                            provenance: String,
                            confidenceScore: Double,
                            notes: Option[String],
                            status: String,
                          )
