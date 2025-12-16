package utils

import models.domain.genomics.{MutationType, NamingStatus, StrCoordinates, VariantV2}
import org.scalatestplus.play.PlaySpec
import play.api.libs.json.Json

class VariantViewUtilsSpec extends PlaySpec {

  "VariantViewUtils" should {
    "format position correctly for STRs" in {
      val strCoords = StrCoordinates(
        contig = "chrY",
        start = 100,
        end = 110,
        period = 4,
        repeatMotif = Some("ATCG"),
        referenceRepeats = Some(10)
      )
      
      val variant = VariantV2(
        canonicalName = Some("DYS393"),
        mutationType = MutationType.STR,
        namingStatus = NamingStatus.Named,
        coordinates = Json.obj("GRCh38" -> Json.toJson(strCoords))
      )
      
      // Current behavior fails this, returns "chrY:0"
      VariantViewUtils.formatPosition(variant, "GRCh38") mustBe "chrY:100-110"
      
      val (ref, alt) = VariantViewUtils.formatAllelesTuple(variant, "GRCh38")
      ref mustBe "ATCG x 10"
      alt mustBe "?"
    }

    "format alleles correctly for STRs with N/A motif" in {
      val strCoords = StrCoordinates(
        contig = "chrY",
        start = 200,
        end = 210,
        period = 4,
        repeatMotif = Some("N/A"), // Motif explicitly "N/A"
        referenceRepeats = Some(43)
      )
      
      val variant = VariantV2(
        canonicalName = Some("DYS385_2"),
        mutationType = MutationType.STR,
        namingStatus = NamingStatus.Named,
        coordinates = Json.obj("GRCh38" -> Json.toJson(strCoords))
      )
      
      val (ref, alt) = VariantViewUtils.formatAllelesTuple(variant, "GRCh38")
      ref mustBe "(repeats: 43)"
      alt mustBe "?"
    }

    "format position correctly for SNPs" in {
       val variant = VariantV2.snp("rs123", "GRCh38", "chr1", 500, "A", "T")
       VariantViewUtils.formatPosition(variant, "GRCh38") mustBe "chr1:500"
    }
  }
}
