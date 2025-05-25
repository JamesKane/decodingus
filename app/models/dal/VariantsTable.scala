package models.dal

import models.dal.domain.GenbankContigsTable
import models.Variant
import models.domain.GenbankContig
import slick.jdbc.PostgresProfile.api.*

/**
 * Represents the `variant` table in the database, which stores information about genetic variants.
 *
 * This table includes genomic variant details such as position, alleles, type, optional identifiers, and
 * associated metadata. It is linked to the `GenbankContigsTable` through a foreign key and enforces
 * uniqueness constraints on specific columns to ensure data integrity.
 *
 * @constructor Creates an instance of the `VariantsTable` class.
 * @param tag A Slick `Tag` object used to scope and reference the table within a database schema.
 *
 *            Schema details:
 *            - Table name: `variant`
 *            - Columns:
 *   - `variantId`: The primary key for the table, an auto-incrementing integer serving as a unique identifier for each variant.
 *   - `genbankContigId`: A foreign key referencing the `GenbankContigsTable`, indicating the genomic contig containing the variant.
 *   - `position`: The position of the variant on the genomic contig.
 *   - `referenceAllele`: The reference allele observed at the variant's position.
 *   - `alternateAllele`: The alternate allele representing the variant.
 *   - `variantType`: Specifies the type of the variant (e.g., SNP, insertion, deletion).
 *   - `rsId`: An optional column for the variant's dbSNP identifier (`rs` ID).
 *   - `commonName`: An optional column for a common name or description of the variant.
 *     - Primary key:
 *   - `variantId`
 *     - Foreign keys:
 *   - `genbankContigFK`: References the `genbank_contig_id` column in the `GenbankContigsTable`. Cascades deletions.
 *     - Indexes and constraints:
 *   - `uniqueVariant`: Enforces a unique constraint on the combination of `genbankContigId`, `position`,
 *     `referenceAllele`, and `alternateAllele`.
 *
 * Mapping:
 *            - Maps to the `Variant` case class, representing the domain model for a variant. The mapping includes all columns,
 *              with `variantId` being optional.
 */
class VariantsTable(tag: Tag) extends Table[Variant](tag, "variant") {
  def variantId = column[Int]("variant_id", O.PrimaryKey, O.AutoInc)

  def genbankContigId = column[Int]("genbank_contig_id")

  def position = column[Int]("position")

  def referenceAllele = column[String]("reference_allele")

  def alternateAllele = column[String]("alternate_allele")

  def variantType = column[String]("variant_type")

  def rsId = column[Option[String]]("rs_id")

  def commonName = column[Option[String]]("common_name")

  def * = (variantId.?, genbankContigId, position, referenceAllele, alternateAllele, variantType, rsId, commonName).mapTo[Variant]

  def genbankContigFK = foreignKey("genbank_contig_fk", genbankContigId, TableQuery[GenbankContigsTable])(_.genbankContigId, onDelete = ForeignKeyAction.Cascade)

  def uniqueVariant = index("unique_variant", (genbankContigId, position, referenceAllele, alternateAllele), unique = true)
}
