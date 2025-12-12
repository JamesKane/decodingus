package models.api.haplogroups

import models.HaplogroupType
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.must.Matchers
import play.api.libs.json.{JsError, JsSuccess, Json}

class TreeMergeModelsSpec extends AnyFunSpec with Matchers {

  describe("VariantInput") {

    describe("JSON serialization") {

      it("should deserialize a simple variant") {
        val json = Json.parse("""{"name": "M207"}""")
        json.validate[VariantInput] match {
          case JsSuccess(v, _) =>
            v.name mustBe "M207"
            v.aliases mustBe List.empty
          case JsError(errors) => fail(s"Parse failed: $errors")
        }
      }

      it("should deserialize a variant with aliases") {
        val json = Json.parse("""{"name": "M207", "aliases": ["Page37", "UTY2"]}""")
        json.validate[VariantInput] match {
          case JsSuccess(v, _) =>
            v.name mustBe "M207"
            v.aliases mustBe List("Page37", "UTY2")
          case JsError(errors) => fail(s"Parse failed: $errors")
        }
      }

      it("should serialize to JSON") {
        val variant = VariantInput("M207", List("Page37", "UTY2"))
        val json = Json.toJson(variant)
        (json \ "name").as[String] mustBe "M207"
        (json \ "aliases").as[List[String]] mustBe List("Page37", "UTY2")
      }
    }
  }

  describe("PhyloNodeInput") {

    describe("JSON serialization") {

      it("should deserialize a simple node with variant objects") {
        val json = Json.parse("""{
          "name": "R1b-L21",
          "variants": [{"name": "L21"}, {"name": "S145"}]
        }""")

        json.validate[PhyloNodeInput] match {
          case JsSuccess(node, _) =>
            node.name mustBe "R1b-L21"
            node.variants.map(_.name) mustBe List("L21", "S145")
            node.children mustBe List.empty
          case JsError(errors) => fail(s"Parse failed: $errors")
        }
      }

      it("should deserialize a node with variant aliases") {
        val json = Json.parse("""{
          "name": "R",
          "variants": [{"name": "M207", "aliases": ["Page37", "UTY2"]}]
        }""")

        json.validate[PhyloNodeInput] match {
          case JsSuccess(node, _) =>
            node.variants must have size 1
            node.variants.head.name mustBe "M207"
            node.variants.head.aliases mustBe List("Page37", "UTY2")
          case JsError(errors) => fail(s"Parse failed: $errors")
        }
      }

      it("should deserialize node with all age fields") {
        val json = Json.parse("""{
          "name": "R1b-L21",
          "variants": [{"name": "L21"}],
          "formedYbp": 4500,
          "formedYbpLower": 4200,
          "formedYbpUpper": 4800,
          "tmrcaYbp": 4000,
          "tmrcaYbpLower": 3700,
          "tmrcaYbpUpper": 4300
        }""")

        json.validate[PhyloNodeInput] match {
          case JsSuccess(node, _) =>
            node.formedYbp mustBe Some(4500)
            node.formedYbpLower mustBe Some(4200)
            node.formedYbpUpper mustBe Some(4800)
            node.tmrcaYbp mustBe Some(4000)
            node.tmrcaYbpLower mustBe Some(3700)
            node.tmrcaYbpUpper mustBe Some(4300)
          case JsError(errors) => fail(s"Parse failed: $errors")
        }
      }

      it("should deserialize nested children") {
        val json = Json.parse("""{
          "name": "R1b-L21",
          "variants": [{"name": "L21"}],
          "children": [
            {
              "name": "R1b-DF13",
              "variants": [{"name": "DF13"}],
              "children": [
                {
                  "name": "R1b-Z39589",
                  "variants": [{"name": "Z39589"}]
                }
              ]
            }
          ]
        }""")

        json.validate[PhyloNodeInput] match {
          case JsSuccess(node, _) =>
            node.name mustBe "R1b-L21"
            node.children must have size 1
            node.children.head.name mustBe "R1b-DF13"
            node.children.head.children must have size 1
            node.children.head.children.head.name mustBe "R1b-Z39589"
          case JsError(errors) => fail(s"Parse failed: $errors")
        }
      }

      it("should serialize to JSON") {
        val node = PhyloNodeInput(
          name = "R1b-L21",
          variants = List(VariantInput("L21"), VariantInput("S145")),
          formedYbp = Some(4500),
          children = List(
            PhyloNodeInput(name = "R1b-DF13", variants = List(VariantInput("DF13")))
          )
        )

        val json = Json.toJson(node)

        (json \ "name").as[String] mustBe "R1b-L21"
        (json \ "variants").as[List[VariantInput]].map(_.name) mustBe List("L21", "S145")
        (json \ "formedYbp").as[Int] mustBe 4500
        (json \ "children").as[List[PhyloNodeInput]] must have size 1
      }

      it("should handle empty variants list") {
        val json = Json.parse("""{"name": "Test"}""")

        json.validate[PhyloNodeInput] match {
          case JsSuccess(node, _) =>
            node.variants mustBe List.empty
            node.children mustBe List.empty
          case JsError(errors) => fail(s"Parse failed: $errors")
        }
      }
    }
  }

  describe("SourcePriorityConfig") {

    it("should deserialize with priority list") {
      val json = Json.parse("""{
        "sourcePriorities": ["ISOGG", "ytree.net", "DecodingUs"],
        "defaultPriority": 50
      }""")

      json.validate[SourcePriorityConfig] match {
        case JsSuccess(config, _) =>
          config.sourcePriorities mustBe List("ISOGG", "ytree.net", "DecodingUs")
          config.defaultPriority mustBe 50
        case JsError(errors) => fail(s"Parse failed: $errors")
      }
    }

    it("should use default priority of 100") {
      val json = Json.parse("""{
        "sourcePriorities": ["ISOGG"]
      }""")

      json.validate[SourcePriorityConfig] match {
        case JsSuccess(config, _) =>
          config.defaultPriority mustBe 100
        case JsError(errors) => fail(s"Parse failed: $errors")
      }
    }
  }

  describe("ConflictStrategy") {

    it("should deserialize higher_priority_wins") {
      val json = Json.parse("\"higher_priority_wins\"")

      json.validate[ConflictStrategy] match {
        case JsSuccess(strategy, _) =>
          strategy mustBe ConflictStrategy.HigherPriorityWins
        case JsError(errors) => fail(s"Parse failed: $errors")
      }
    }

    it("should deserialize keep_existing") {
      val json = Json.parse("\"keep_existing\"")

      json.validate[ConflictStrategy] match {
        case JsSuccess(strategy, _) =>
          strategy mustBe ConflictStrategy.KeepExisting
        case JsError(errors) => fail(s"Parse failed: $errors")
      }
    }

    it("should deserialize always_update") {
      val json = Json.parse("\"always_update\"")

      json.validate[ConflictStrategy] match {
        case JsSuccess(strategy, _) =>
          strategy mustBe ConflictStrategy.AlwaysUpdate
        case JsError(errors) => fail(s"Parse failed: $errors")
      }
    }

    it("should fail for unknown strategy") {
      val json = Json.parse("\"invalid_strategy\"")

      // The implementation throws an exception for invalid strategies
      an[IllegalArgumentException] must be thrownBy {
        json.as[ConflictStrategy]
      }
    }

    it("should serialize strategies correctly") {
      Json.toJson[ConflictStrategy](ConflictStrategy.HigherPriorityWins).as[String] mustBe "higher_priority_wins"
      Json.toJson[ConflictStrategy](ConflictStrategy.KeepExisting).as[String] mustBe "keep_existing"
      Json.toJson[ConflictStrategy](ConflictStrategy.AlwaysUpdate).as[String] mustBe "always_update"
    }
  }

  describe("TreeMergeRequest") {

    it("should deserialize a full merge request") {
      val json = Json.parse("""{
        "haplogroupType": "Y",
        "sourceTree": {
          "name": "R1b",
          "variants": [{"name": "M269"}]
        },
        "sourceName": "ytree.net",
        "priorityConfig": {
          "sourcePriorities": ["ytree.net", "ISOGG"]
        },
        "conflictStrategy": "higher_priority_wins",
        "dryRun": true
      }""")

      json.validate[TreeMergeRequest] match {
        case JsSuccess(request, _) =>
          request.haplogroupType mustBe HaplogroupType.Y
          request.sourceTree.name mustBe "R1b"
          request.sourceName mustBe "ytree.net"
          request.priorityConfig mustBe defined
          request.conflictStrategy mustBe Some(ConflictStrategy.HigherPriorityWins)
          request.dryRun mustBe true
        case JsError(errors) => fail(s"Parse failed: $errors")
      }
    }

    it("should deserialize minimal merge request") {
      val json = Json.parse("""{
        "haplogroupType": "MT",
        "sourceTree": {"name": "H"},
        "sourceName": "test"
      }""")

      json.validate[TreeMergeRequest] match {
        case JsSuccess(request, _) =>
          request.haplogroupType mustBe HaplogroupType.MT
          request.priorityConfig mustBe None
          request.conflictStrategy mustBe None
          request.dryRun mustBe false
        case JsError(errors) => fail(s"Parse failed: $errors")
      }
    }

    it("should fail for invalid haplogroup type") {
      val json = Json.parse("""{
        "haplogroupType": "INVALID",
        "sourceTree": {"name": "Test"},
        "sourceName": "test"
      }""")

      // The implementation throws an exception for invalid haplogroup types
      an[IllegalArgumentException] must be thrownBy {
        json.as[TreeMergeRequest]
      }
    }
  }

  describe("SubtreeMergeRequest") {

    it("should deserialize a subtree merge request") {
      val json = Json.parse("""{
        "haplogroupType": "Y",
        "anchorHaplogroupName": "R1b",
        "sourceTree": {
          "name": "R1b-L21",
          "variants": [{"name": "L21"}]
        },
        "sourceName": "ytree.net",
        "dryRun": false
      }""")

      json.validate[SubtreeMergeRequest] match {
        case JsSuccess(request, _) =>
          request.haplogroupType mustBe HaplogroupType.Y
          request.anchorHaplogroupName mustBe "R1b"
          request.sourceTree.name mustBe "R1b-L21"
          request.sourceName mustBe "ytree.net"
        case JsError(errors) => fail(s"Parse failed: $errors")
      }
    }
  }

  describe("MergePreviewRequest") {

    it("should deserialize with optional anchor") {
      val json = Json.parse("""{
        "haplogroupType": "Y",
        "anchorHaplogroupName": "R1b",
        "sourceTree": {"name": "Test"},
        "sourceName": "test"
      }""")

      json.validate[MergePreviewRequest] match {
        case JsSuccess(request, _) =>
          request.anchorHaplogroupName mustBe Some("R1b")
        case JsError(errors) => fail(s"Parse failed: $errors")
      }
    }

    it("should deserialize without anchor") {
      val json = Json.parse("""{
        "haplogroupType": "Y",
        "sourceTree": {"name": "Test"},
        "sourceName": "test"
      }""")

      json.validate[MergePreviewRequest] match {
        case JsSuccess(request, _) =>
          request.anchorHaplogroupName mustBe None
        case JsError(errors) => fail(s"Parse failed: $errors")
      }
    }
  }

  describe("MergeStatistics") {

    it("should serialize all fields") {
      val stats = MergeStatistics(
        nodesProcessed = 100,
        nodesCreated = 50,
        nodesUpdated = 30,
        nodesUnchanged = 20,
        variantsAdded = 200,
        variantsUpdated = 50,
        relationshipsCreated = 49,
        relationshipsUpdated = 10,
        splitOperations = 5
      )

      val json = Json.toJson(stats)

      (json \ "nodesProcessed").as[Int] mustBe 100
      (json \ "nodesCreated").as[Int] mustBe 50
      (json \ "nodesUpdated").as[Int] mustBe 30
      (json \ "nodesUnchanged").as[Int] mustBe 20
      (json \ "variantsAdded").as[Int] mustBe 200
      (json \ "variantsUpdated").as[Int] mustBe 50
      (json \ "relationshipsCreated").as[Int] mustBe 49
      (json \ "relationshipsUpdated").as[Int] mustBe 10
      (json \ "splitOperations").as[Int] mustBe 5
    }

    it("should create empty statistics") {
      val empty = MergeStatistics.empty

      empty.nodesProcessed mustBe 0
      empty.nodesCreated mustBe 0
      empty.nodesUpdated mustBe 0
      empty.nodesUnchanged mustBe 0
    }

    it("should combine statistics correctly") {
      val stats1 = MergeStatistics(10, 5, 3, 2, 20, 5, 4, 1, 0)
      val stats2 = MergeStatistics(20, 10, 6, 4, 40, 10, 9, 2, 1)

      val combined = MergeStatistics.combine(stats1, stats2)

      combined.nodesProcessed mustBe 30
      combined.nodesCreated mustBe 15
      combined.nodesUpdated mustBe 9
      combined.nodesUnchanged mustBe 6
      combined.variantsAdded mustBe 60
      combined.variantsUpdated mustBe 15
      combined.relationshipsCreated mustBe 13
      combined.relationshipsUpdated mustBe 3
      combined.splitOperations mustBe 1
    }
  }

  describe("MergeConflict") {

    it("should serialize conflict details") {
      val conflict = MergeConflict(
        haplogroupName = "R1b-L21",
        field = "formedYbp",
        existingValue = "4500",
        newValue = "4800",
        resolution = "updated",
        existingSource = "ISOGG",
        newSource = "ytree.net"
      )

      val json = Json.toJson(conflict)

      (json \ "haplogroupName").as[String] mustBe "R1b-L21"
      (json \ "field").as[String] mustBe "formedYbp"
      (json \ "existingValue").as[String] mustBe "4500"
      (json \ "newValue").as[String] mustBe "4800"
      (json \ "resolution").as[String] mustBe "updated"
      (json \ "existingSource").as[String] mustBe "ISOGG"
      (json \ "newSource").as[String] mustBe "ytree.net"
    }

    it("should round-trip serialize") {
      val original = MergeConflict(
        haplogroupName = "Test",
        field = "description",
        existingValue = "old",
        newValue = "new",
        resolution = "kept_existing",
        existingSource = "A",
        newSource = "B"
      )

      val restored = Json.toJson(original).as[MergeConflict]

      restored mustBe original
    }
  }

  describe("SplitOperation") {

    it("should serialize split details") {
      val split = SplitOperation(
        parentName = "R1b-L21",
        newIntermediateName = "R1b-L21a",
        variantsRedistributed = List("V1", "V2"),
        childrenReassigned = List("R1b-Z39589", "R1b-Z39590"),
        source = "ytree.net"
      )

      val json = Json.toJson(split)

      (json \ "parentName").as[String] mustBe "R1b-L21"
      (json \ "newIntermediateName").as[String] mustBe "R1b-L21a"
      (json \ "variantsRedistributed").as[List[String]] mustBe List("V1", "V2")
      (json \ "childrenReassigned").as[List[String]] mustBe List("R1b-Z39589", "R1b-Z39590")
      (json \ "source").as[String] mustBe "ytree.net"
    }
  }

  describe("TreeMergeResponse") {

    it("should serialize successful response") {
      val response = TreeMergeResponse(
        success = true,
        message = "Merge completed successfully",
        statistics = MergeStatistics(10, 5, 3, 2, 20, 5, 4, 1, 0),
        conflicts = List.empty,
        splits = List.empty,
        errors = List.empty
      )

      val json = Json.toJson(response)

      (json \ "success").as[Boolean] mustBe true
      (json \ "message").as[String] mustBe "Merge completed successfully"
      (json \ "statistics" \ "nodesProcessed").as[Int] mustBe 10
      (json \ "conflicts").as[List[MergeConflict]] mustBe empty
    }

    it("should create failure response") {
      val response = TreeMergeResponse.failure(
        "Merge failed due to validation error",
        List("Error 1", "Error 2")
      )

      response.success mustBe false
      response.message mustBe "Merge failed due to validation error"
      response.errors mustBe List("Error 1", "Error 2")
      response.statistics mustBe MergeStatistics.empty
    }

    it("should serialize response with conflicts and errors") {
      val response = TreeMergeResponse(
        success = false,
        message = "Completed with warnings",
        statistics = MergeStatistics.empty,
        conflicts = List(
          MergeConflict("Node1", "field1", "old", "new", "kept", "A", "B")
        ),
        splits = List.empty,
        errors = List("Warning: some nodes skipped")
      )

      val json = Json.toJson(response)

      (json \ "conflicts").as[List[MergeConflict]] must have size 1
      (json \ "errors").as[List[String]] must have size 1
    }
  }

  describe("MergePreviewResponse") {

    it("should serialize preview with all details") {
      val response = MergePreviewResponse(
        statistics = MergeStatistics(10, 5, 3, 2, 20, 5, 4, 1, 0),
        conflicts = List(
          MergeConflict("Node1", "formedYbp", "4500", "4800", "will_update", "A", "B")
        ),
        splits = List.empty,
        newNodes = List("NewNode1", "NewNode2"),
        updatedNodes = List("UpdatedNode1"),
        unchangedNodes = List("UnchangedNode1", "UnchangedNode2")
      )

      val json = Json.toJson(response)

      (json \ "newNodes").as[List[String]] mustBe List("NewNode1", "NewNode2")
      (json \ "updatedNodes").as[List[String]] mustBe List("UpdatedNode1")
      (json \ "unchangedNodes").as[List[String]] mustBe List("UnchangedNode1", "UnchangedNode2")
      (json \ "statistics" \ "nodesCreated").as[Int] mustBe 5
    }
  }

  describe("HaplogroupType in requests") {

    it("should accept Y haplogroup type") {
      val json = Json.parse("""{
        "haplogroupType": "Y",
        "sourceTree": {"name": "R1b"},
        "sourceName": "test"
      }""")

      json.validate[TreeMergeRequest] match {
        case JsSuccess(request, _) =>
          request.haplogroupType mustBe HaplogroupType.Y
        case JsError(errors) => fail(s"Parse failed: $errors")
      }
    }

    it("should accept MT haplogroup type") {
      val json = Json.parse("""{
        "haplogroupType": "MT",
        "sourceTree": {"name": "H"},
        "sourceName": "test"
      }""")

      json.validate[TreeMergeRequest] match {
        case JsSuccess(request, _) =>
          request.haplogroupType mustBe HaplogroupType.MT
        case JsError(errors) => fail(s"Parse failed: $errors")
      }
    }
  }
}
