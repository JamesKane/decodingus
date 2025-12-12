package models.domain.haplogroups

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.must.Matchers
import play.api.libs.json.Json

import java.time.LocalDateTime

class HaplogroupProvenanceSpec extends AnyFunSpec with Matchers {

  describe("HaplogroupProvenance") {

    describe("factory methods") {

      it("should create provenance for a new node with source") {
        val provenance = HaplogroupProvenance.forNewNode("ytree.net", Seq("L21", "S145"))

        provenance.primaryCredit mustBe "ytree.net"
        provenance.nodeProvenance mustBe Set("ytree.net")
        provenance.variantProvenance mustBe Map(
          "L21" -> Set("ytree.net"),
          "S145" -> Set("ytree.net")
        )
        provenance.lastMergedFrom mustBe Some("ytree.net")
        provenance.lastMergedAt mustBe defined
      }

      it("should create provenance for a new node without variants") {
        val provenance = HaplogroupProvenance.forNewNode("ISOGG")

        provenance.primaryCredit mustBe "ISOGG"
        provenance.nodeProvenance mustBe Set("ISOGG")
        provenance.variantProvenance mustBe Map.empty
      }

      it("should create empty provenance") {
        val provenance = HaplogroupProvenance.empty

        provenance.primaryCredit mustBe ""
        provenance.nodeProvenance mustBe Set.empty
        provenance.variantProvenance mustBe Map.empty
        provenance.lastMergedAt mustBe None
        provenance.lastMergedFrom mustBe None
      }
    }

    describe("addNodeSource") {

      it("should add a new source to nodeProvenance") {
        val provenance = HaplogroupProvenance.forNewNode("ISOGG")
        val updated = provenance.addNodeSource("ytree.net")

        updated.nodeProvenance must contain allOf ("ISOGG", "ytree.net")
        updated.primaryCredit mustBe "ISOGG" // Should not change
      }

      it("should not duplicate existing sources") {
        val provenance = HaplogroupProvenance.forNewNode("ISOGG")
        val updated = provenance.addNodeSource("ISOGG")

        updated.nodeProvenance mustBe Set("ISOGG")
      }

      it("should accumulate multiple sources") {
        val provenance = HaplogroupProvenance.forNewNode("source1")
          .addNodeSource("source2")
          .addNodeSource("source3")

        provenance.nodeProvenance must have size 3
        provenance.nodeProvenance must contain allOf ("source1", "source2", "source3")
      }
    }

    describe("addVariantSource") {

      it("should add source attribution for a new variant") {
        val provenance = HaplogroupProvenance.forNewNode("ISOGG")
        val updated = provenance.addVariantSource("M269", "ytree.net")

        updated.variantProvenance must contain key "M269"
        updated.variantProvenance("M269") must contain("ytree.net")
      }

      it("should add additional sources to existing variants") {
        val provenance = HaplogroupProvenance.forNewNode("ISOGG", Seq("L21"))
        val updated = provenance.addVariantSource("L21", "ytree.net")

        updated.variantProvenance("L21") must contain allOf ("ISOGG", "ytree.net")
      }

      it("should not duplicate sources for the same variant") {
        val provenance = HaplogroupProvenance.forNewNode("ISOGG", Seq("L21"))
        val updated = provenance.addVariantSource("L21", "ISOGG")

        updated.variantProvenance("L21") mustBe Set("ISOGG")
      }
    }

    describe("merge") {

      it("should combine nodeProvenance from both records") {
        val prov1 = HaplogroupProvenance(
          primaryCredit = "ISOGG",
          nodeProvenance = Set("ISOGG", "DecodingUs")
        )
        val prov2 = HaplogroupProvenance(
          primaryCredit = "ytree.net",
          nodeProvenance = Set("ytree.net", "researcher")
        )

        val merged = prov1.merge(prov2)

        merged.nodeProvenance must contain allOf ("ISOGG", "DecodingUs", "ytree.net", "researcher")
      }

      it("should preserve primary credit from the first provenance") {
        val prov1 = HaplogroupProvenance(primaryCredit = "ISOGG")
        val prov2 = HaplogroupProvenance(primaryCredit = "ytree.net")

        val merged = prov1.merge(prov2)

        merged.primaryCredit mustBe "ISOGG"
      }

      it("should combine variantProvenance") {
        val prov1 = HaplogroupProvenance(
          primaryCredit = "ISOGG",
          variantProvenance = Map("L21" -> Set("ISOGG"), "M269" -> Set("ISOGG"))
        )
        val prov2 = HaplogroupProvenance(
          primaryCredit = "ytree.net",
          variantProvenance = Map("L21" -> Set("ytree.net"), "DF13" -> Set("ytree.net"))
        )

        val merged = prov1.merge(prov2)

        merged.variantProvenance("L21") must contain allOf ("ISOGG", "ytree.net")
        merged.variantProvenance("M269") mustBe Set("ISOGG")
        merged.variantProvenance("DF13") mustBe Set("ytree.net")
      }

      it("should take the most recent lastMergedAt timestamp") {
        val earlier = LocalDateTime.now().minusDays(1)
        val later = LocalDateTime.now()

        val prov1 = HaplogroupProvenance(
          primaryCredit = "A",
          lastMergedAt = Some(earlier)
        )
        val prov2 = HaplogroupProvenance(
          primaryCredit = "B",
          lastMergedAt = Some(later)
        )

        val merged = prov1.merge(prov2)

        merged.lastMergedAt mustBe Some(later)
      }

      it("should prefer lastMergedFrom from the second provenance") {
        val prov1 = HaplogroupProvenance(
          primaryCredit = "A",
          lastMergedFrom = Some("source1")
        )
        val prov2 = HaplogroupProvenance(
          primaryCredit = "B",
          lastMergedFrom = Some("source2")
        )

        val merged = prov1.merge(prov2)

        merged.lastMergedFrom mustBe Some("source2")
      }

      it("should handle merging with empty provenance") {
        val prov1 = HaplogroupProvenance.forNewNode("ISOGG", Seq("L21"))
        val prov2 = HaplogroupProvenance.empty

        val merged = prov1.merge(prov2)

        merged.primaryCredit mustBe "ISOGG"
        merged.nodeProvenance mustBe Set("ISOGG")
        merged.variantProvenance mustBe Map("L21" -> Set("ISOGG"))
      }
    }

    describe("withMergeInfo") {

      it("should update merge timestamp and source") {
        val provenance = HaplogroupProvenance.forNewNode("ISOGG")
        val now = LocalDateTime.now()
        val updated = provenance.withMergeInfo("ytree.net", now)

        updated.lastMergedAt mustBe Some(now)
        updated.lastMergedFrom mustBe Some("ytree.net")
        updated.primaryCredit mustBe "ISOGG" // Should not change
      }

      it("should overwrite previous merge info") {
        val earlier = LocalDateTime.now().minusHours(1)
        val later = LocalDateTime.now()

        val provenance = HaplogroupProvenance.forNewNode("ISOGG")
          .withMergeInfo("source1", earlier)
          .withMergeInfo("source2", later)

        provenance.lastMergedAt mustBe Some(later)
        provenance.lastMergedFrom mustBe Some("source2")
      }
    }

    describe("shouldPreserveCredit") {

      it("should return true for ISOGG credit") {
        HaplogroupProvenance.shouldPreserveCredit("ISOGG") mustBe true
      }

      it("should be case-insensitive for ISOGG") {
        HaplogroupProvenance.shouldPreserveCredit("isogg") mustBe true
        HaplogroupProvenance.shouldPreserveCredit("IsoGG") mustBe true
        HaplogroupProvenance.shouldPreserveCredit("Isogg") mustBe true
      }

      it("should return false for non-ISOGG sources") {
        HaplogroupProvenance.shouldPreserveCredit("ytree.net") mustBe false
        HaplogroupProvenance.shouldPreserveCredit("DecodingUs") mustBe false
        HaplogroupProvenance.shouldPreserveCredit("researcher") mustBe false
      }

      it("should return false for empty string") {
        HaplogroupProvenance.shouldPreserveCredit("") mustBe false
      }
    }

    describe("JSON serialization") {

      it("should serialize to JSON correctly") {
        val provenance = HaplogroupProvenance(
          primaryCredit = "ISOGG",
          nodeProvenance = Set("ISOGG", "ytree.net"),
          variantProvenance = Map("L21" -> Set("ISOGG", "ytree.net")),
          lastMergedAt = Some(LocalDateTime.of(2025, 12, 12, 10, 30, 0)),
          lastMergedFrom = Some("ytree.net")
        )

        val json = Json.toJson(provenance)

        (json \ "primaryCredit").as[String] mustBe "ISOGG"
        (json \ "nodeProvenance").as[Set[String]] must contain allOf ("ISOGG", "ytree.net")
        (json \ "lastMergedFrom").as[String] mustBe "ytree.net"
      }

      it("should deserialize from JSON correctly") {
        val jsonString = """{
          "primaryCredit": "ISOGG",
          "nodeProvenance": ["ISOGG", "ytree.net"],
          "variantProvenance": {"L21": ["ISOGG", "ytree.net"]},
          "lastMergedFrom": "ytree.net"
        }"""

        val provenance = Json.parse(jsonString).as[HaplogroupProvenance]

        provenance.primaryCredit mustBe "ISOGG"
        provenance.nodeProvenance must contain allOf ("ISOGG", "ytree.net")
        provenance.variantProvenance("L21") must contain allOf ("ISOGG", "ytree.net")
        provenance.lastMergedFrom mustBe Some("ytree.net")
      }

      it("should round-trip serialize and deserialize") {
        val original = HaplogroupProvenance.forNewNode("test-source", Seq("V1", "V2"))

        val json = Json.toJson(original)
        val restored = json.as[HaplogroupProvenance]

        restored.primaryCredit mustBe original.primaryCredit
        restored.nodeProvenance mustBe original.nodeProvenance
        restored.variantProvenance mustBe original.variantProvenance
        restored.lastMergedFrom mustBe original.lastMergedFrom
      }

      it("should handle empty collections in JSON") {
        val jsonString = """{
          "primaryCredit": "test",
          "nodeProvenance": [],
          "variantProvenance": {}
        }"""

        val provenance = Json.parse(jsonString).as[HaplogroupProvenance]

        provenance.nodeProvenance mustBe Set.empty
        provenance.variantProvenance mustBe Map.empty
      }

      it("should handle missing optional fields") {
        val jsonString = """{
          "primaryCredit": "test"
        }"""

        val provenance = Json.parse(jsonString).as[HaplogroupProvenance]

        provenance.primaryCredit mustBe "test"
        provenance.nodeProvenance mustBe Set.empty
        provenance.variantProvenance mustBe Map.empty
        provenance.lastMergedAt mustBe None
        provenance.lastMergedFrom mustBe None
      }
    }

    describe("immutability") {

      it("should not mutate original when adding node source") {
        val original = HaplogroupProvenance.forNewNode("ISOGG")
        val modified = original.addNodeSource("ytree.net")

        original.nodeProvenance must not contain "ytree.net"
        modified.nodeProvenance must contain("ytree.net")
      }

      it("should not mutate original when adding variant source") {
        val original = HaplogroupProvenance.forNewNode("ISOGG")
        val modified = original.addVariantSource("L21", "ytree.net")

        original.variantProvenance must not contain key ("L21")
        modified.variantProvenance must contain key "L21"
      }

      it("should not mutate original when merging") {
        val prov1 = HaplogroupProvenance.forNewNode("A")
        val prov2 = HaplogroupProvenance.forNewNode("B")
        val merged = prov1.merge(prov2)

        prov1.nodeProvenance mustBe Set("A")
        prov2.nodeProvenance mustBe Set("B")
        merged.nodeProvenance must contain allOf ("A", "B")
      }
    }
  }
}
