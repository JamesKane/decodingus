package models.domain.genomics

import org.scalatestplus.play.PlaySpec
import play.api.libs.json.Json

import java.util.UUID

class OriginalHaplogroupEntrySpec extends PlaySpec {

  val hapResult: HaplogroupResult = HaplogroupResult("R-M269", 0.99, 100, 0, 50, 5, Seq("R", "R-M269"))

  "OriginalHaplogroupEntry" should {

    "serialize to JSON" in {
      val entry = OriginalHaplogroupEntry(
        publicationId = 42,
        yHaplogroupResult = Some(hapResult),
        mtHaplogroupResult = None,
        notes = Some("From study X")
      )
      val json = Json.toJson(entry)
      (json \ "publicationId").as[Int] mustBe 42
      (json \ "yHaplogroupResult" \ "haplogroupName").as[String] mustBe "R-M269"
      (json \ "notes").as[String] mustBe "From study X"
    }

    "deserialize from JSON" in {
      val json = Json.parse("""{"publicationId": 42, "yHaplogroupResult": {"haplogroupName": "R-M269", "score": 0.99, "matchingSnps": 100, "mismatchingSnps": 0, "ancestralMatches": 50, "treeDepth": 5, "lineagePath": ["R", "R-M269"]}}""")
      val entry = json.as[OriginalHaplogroupEntry]
      entry.publicationId mustBe 42
      entry.yHaplogroupResult mustBe defined
      entry.mtHaplogroupResult mustBe None
    }

    "round-trip through JSON" in {
      val entry = OriginalHaplogroupEntry(10, Some(hapResult), Some(hapResult), Some("notes"))
      val roundTripped = Json.toJson(entry).as[OriginalHaplogroupEntry]
      roundTripped mustBe entry
    }
  }

  "Biosample.getOriginalHaplogroupEntries" should {

    val baseBiosample = Biosample(
      id = Some(1), sampleGuid = UUID.randomUUID(), sampleAccession = "SAMEA001",
      description = "Test", alias = None, centerName = "Center",
      specimenDonorId = None
    )

    "return empty for no haplogroups" in {
      baseBiosample.getOriginalHaplogroupEntries mustBe empty
    }

    "return entries from JSONB" in {
      val entries = Seq(
        OriginalHaplogroupEntry(10, Some(hapResult), None, None),
        OriginalHaplogroupEntry(20, None, Some(hapResult), Some("mt study"))
      )
      val bs = baseBiosample.copy(originalHaplogroups = Some(Json.toJson(entries)))
      bs.getOriginalHaplogroupEntries must have size 2
    }

    "find by publication ID" in {
      val entries = Seq(
        OriginalHaplogroupEntry(10, Some(hapResult), None, None),
        OriginalHaplogroupEntry(20, None, Some(hapResult), None)
      )
      val bs = baseBiosample.copy(originalHaplogroups = Some(Json.toJson(entries)))
      bs.findHaplogroupByPublication(10) mustBe defined
      bs.findHaplogroupByPublication(10).get.publicationId mustBe 10
      bs.findHaplogroupByPublication(99) mustBe None
    }

    "add entry with withHaplogroupEntry" in {
      val entry1 = OriginalHaplogroupEntry(10, Some(hapResult), None, None)
      val bs = baseBiosample.withHaplogroupEntry(entry1)
      bs.getOriginalHaplogroupEntries must have size 1

      val entry2 = OriginalHaplogroupEntry(20, None, Some(hapResult), None)
      val bs2 = bs.withHaplogroupEntry(entry2)
      bs2.getOriginalHaplogroupEntries must have size 2
    }

    "replace entry for same publication" in {
      val entry1 = OriginalHaplogroupEntry(10, Some(hapResult), None, None)
      val bs = baseBiosample.withHaplogroupEntry(entry1)

      val updated = OriginalHaplogroupEntry(10, Some(hapResult), Some(hapResult), Some("updated"))
      val bs2 = bs.withHaplogroupEntry(updated)
      bs2.getOriginalHaplogroupEntries must have size 1
      bs2.findHaplogroupByPublication(10).get.notes mustBe Some("updated")
    }

    "remove entry with withoutHaplogroupForPublication" in {
      val entries = Seq(
        OriginalHaplogroupEntry(10, Some(hapResult), None, None),
        OriginalHaplogroupEntry(20, None, Some(hapResult), None)
      )
      val bs = baseBiosample.copy(originalHaplogroups = Some(Json.toJson(entries)))
      val bs2 = bs.withoutHaplogroupForPublication(10)
      bs2.getOriginalHaplogroupEntries must have size 1
      bs2.findHaplogroupByPublication(10) mustBe None
      bs2.findHaplogroupByPublication(20) mustBe defined
    }
  }
}
