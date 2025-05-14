package models.dal

import models.{GenbankContig, Variant}
import slick.jdbc.PostgresProfile.api._

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
