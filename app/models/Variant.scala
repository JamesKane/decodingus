package models

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
