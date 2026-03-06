package services

import helpers.ServiceSpec
import models.HaplogroupType
import models.api.*
import models.domain.genomics.{MutationType, NamingStatus, VariantV2}
import models.domain.haplogroups.Haplogroup
import org.mockito.ArgumentMatchers.{any, anyInt, anyString}
import org.mockito.Mockito.{never, reset, verify, when}
import play.api.libs.json.Json
import repositories.{HaplogroupCoreRepository, HaplogroupVariantRepository}

import java.time.{Instant, LocalDateTime, ZoneId}
import scala.concurrent.Future

class HaplogroupTreeServiceSpec extends ServiceSpec {

  val mockCoreRepo: HaplogroupCoreRepository = mock[HaplogroupCoreRepository]
  val mockVariantRepo: HaplogroupVariantRepository = mock[HaplogroupVariantRepository]

  val service = new HaplogroupTreeService(mockCoreRepo, mockVariantRepo)

  override def beforeEach(): Unit = {
    reset(mockCoreRepo, mockVariantRepo)
  }

  val now: LocalDateTime = LocalDateTime.of(2025, 1, 1, 0, 0)

  def makeHaplogroup(id: Int, name: String, source: String = "backbone"): Haplogroup = Haplogroup(
    id = Some(id), name = name, lineage = Some(s"root>$name"),
    description = None, haplogroupType = HaplogroupType.Y,
    revisionId = 1, source = source, confidenceLevel = "high",
    validFrom = now, validUntil = None,
    formedYbp = Some(5000), tmrcaYbp = Some(4500)
  )

  val rootHg: Haplogroup = makeHaplogroup(1, "R")
  val childHg: Haplogroup = makeHaplogroup(2, "R-M269")
  val grandchildHg: Haplogroup = makeHaplogroup(3, "R-L151", source = "community")

  def makeVariant(id: Int, name: String): VariantV2 = VariantV2(
    variantId = Some(id),
    canonicalName = Some(name),
    mutationType = MutationType.SNP,
    namingStatus = NamingStatus.Unnamed,
    aliases = Json.obj("rs_ids" -> Seq("rs12345"), "common_names" -> Seq(name)),
    coordinates = Json.obj(
      "GRCh38" -> Json.obj("contig" -> "chrY", "position" -> 1000, "ref" -> "A", "alt" -> "G")
    )
  )

  val testVariant: VariantV2 = makeVariant(100, "M269")

  "HaplogroupTreeService" should {

    "buildTreeResponse with ApiRoute includes variants" in {
      when(mockCoreRepo.getHaplogroupByName("R", HaplogroupType.Y)).thenReturn(Future.successful(Some(rootHg)))
      when(mockCoreRepo.getAncestors(1)).thenReturn(Future.successful(Seq.empty))
      when(mockVariantRepo.getHaplogroupVariants(1)).thenReturn(Future.successful(Seq(testVariant)))
      when(mockCoreRepo.getDirectChildren(1)).thenReturn(Future.successful(Seq.empty))

      whenReady(service.buildTreeResponse("R", HaplogroupType.Y, ApiRoute)) { tree =>
        tree.name mustBe "R"
        tree.crumbs mustBe empty
        tree.subclade mustBe defined
        tree.subclade.get.variants must have size 1
        tree.subclade.get.variants.head.name mustBe "M269"
      }
    }

    "buildTreeResponse with FragmentRoute excludes variants" in {
      when(mockCoreRepo.getHaplogroupByName("R", HaplogroupType.Y)).thenReturn(Future.successful(Some(rootHg)))
      when(mockCoreRepo.getAncestors(1)).thenReturn(Future.successful(Seq.empty))
      when(mockVariantRepo.countHaplogroupVariants(1)).thenReturn(Future.successful(3))
      when(mockCoreRepo.getDirectChildren(1)).thenReturn(Future.successful(Seq.empty))

      whenReady(service.buildTreeResponse("R", HaplogroupType.Y, FragmentRoute)) { tree =>
        tree.subclade.get.variants mustBe empty
        tree.subclade.get.variantCount mustBe Some(3)
      }
    }

    "buildTreeResponse includes ancestor breadcrumbs" in {
      when(mockCoreRepo.getHaplogroupByName("R-M269", HaplogroupType.Y)).thenReturn(Future.successful(Some(childHg)))
      when(mockCoreRepo.getAncestors(2)).thenReturn(Future.successful(Seq(rootHg)))
      when(mockVariantRepo.getHaplogroupVariants(2)).thenReturn(Future.successful(Seq.empty))
      when(mockCoreRepo.getDirectChildren(2)).thenReturn(Future.successful(Seq.empty))

      whenReady(service.buildTreeResponse("R-M269", HaplogroupType.Y, ApiRoute)) { tree =>
        tree.crumbs must have size 1
        tree.crumbs.head.label mustBe "R"
      }
    }

    "buildTreeResponse recursively builds children" in {
      when(mockCoreRepo.getHaplogroupByName("R", HaplogroupType.Y)).thenReturn(Future.successful(Some(rootHg)))
      when(mockCoreRepo.getAncestors(1)).thenReturn(Future.successful(Seq.empty))
      when(mockVariantRepo.getHaplogroupVariants(1)).thenReturn(Future.successful(Seq.empty))
      when(mockCoreRepo.getDirectChildren(1)).thenReturn(Future.successful(Seq(childHg)))
      // child level
      when(mockVariantRepo.getHaplogroupVariants(2)).thenReturn(Future.successful(Seq(testVariant)))
      when(mockCoreRepo.getDirectChildren(2)).thenReturn(Future.successful(Seq.empty))

      whenReady(service.buildTreeResponse("R", HaplogroupType.Y, ApiRoute)) { tree =>
        tree.subclade.get.children must have size 1
        tree.subclade.get.children.head.name mustBe "R-M269"
        tree.subclade.get.children.head.variants must have size 1
      }
    }

    "buildTreeResponse resolves variant query to haplogroup name" in {
      // First lookup by name fails, second lookup (after resolution) succeeds
      when(mockCoreRepo.getHaplogroupByName("M269", HaplogroupType.Y)).thenReturn(Future.successful(None))
      when(mockCoreRepo.getHaplogroupByName("R-M269", HaplogroupType.Y)).thenReturn(Future.successful(Some(childHg)))
      // Variant search succeeds (normalizeVariantId returns original for non-rs/non-chr:pos)
      when(mockVariantRepo.findVariants("M269")).thenReturn(Future.successful(Seq(testVariant)))
      when(mockVariantRepo.findHaplogroupsByDefiningVariant("100", HaplogroupType.Y))
        .thenReturn(Future.successful(Seq(childHg)))
      // Then builds tree for resolved name
      when(mockCoreRepo.getAncestors(2)).thenReturn(Future.successful(Seq(rootHg)))
      when(mockVariantRepo.getHaplogroupVariants(2)).thenReturn(Future.successful(Seq.empty))
      when(mockCoreRepo.getDirectChildren(2)).thenReturn(Future.successful(Seq.empty))

      whenReady(service.buildTreeResponse("M269", HaplogroupType.Y, ApiRoute)) { tree =>
        tree.name mustBe "R-M269"
      }
    }

    "buildTreeResponse fails when haplogroup and variant not found" in {
      when(mockCoreRepo.getHaplogroupByName("UNKNOWN", HaplogroupType.Y)).thenReturn(Future.successful(None))
      // normalizeVariantId lowercases the query
      when(mockVariantRepo.findVariants(anyString)).thenReturn(Future.successful(Seq.empty))

      whenReady(service.buildTreeResponse("UNKNOWN", HaplogroupType.Y, ApiRoute).failed) { ex =>
        ex mustBe a[IllegalArgumentException]
        ex.getMessage must include("not found")
      }
    }

    "buildTreeFromVariant returns tree for defining haplogroup" in {
      when(mockVariantRepo.findHaplogroupsByDefiningVariant("M269", HaplogroupType.Y))
        .thenReturn(Future.successful(Seq(childHg)))
      when(mockCoreRepo.getHaplogroupByName("R-M269", HaplogroupType.Y)).thenReturn(Future.successful(Some(childHg)))
      when(mockCoreRepo.getAncestors(2)).thenReturn(Future.successful(Seq(rootHg)))
      when(mockVariantRepo.getHaplogroupVariants(2)).thenReturn(Future.successful(Seq.empty))
      when(mockCoreRepo.getDirectChildren(2)).thenReturn(Future.successful(Seq.empty))

      whenReady(service.buildTreeFromVariant("M269", HaplogroupType.Y, ApiRoute)) { result =>
        result mustBe defined
        result.get.name mustBe "R-M269"
      }
    }

    "buildTreeFromVariant returns None when no haplogroups found" in {
      when(mockVariantRepo.findHaplogroupsByDefiningVariant("FAKE", HaplogroupType.Y))
        .thenReturn(Future.successful(Seq.empty))

      whenReady(service.buildTreeFromVariant("FAKE", HaplogroupType.Y, ApiRoute)) { result =>
        result mustBe None
      }
    }

    "buildTreesFromVariant returns trees for all matching haplogroups" in {
      when(mockVariantRepo.findHaplogroupsByDefiningVariant("M269", HaplogroupType.Y))
        .thenReturn(Future.successful(Seq(childHg, grandchildHg)))
      // For childHg tree
      when(mockCoreRepo.getHaplogroupByName("R-M269", HaplogroupType.Y)).thenReturn(Future.successful(Some(childHg)))
      when(mockCoreRepo.getAncestors(2)).thenReturn(Future.successful(Seq(rootHg)))
      when(mockVariantRepo.getHaplogroupVariants(2)).thenReturn(Future.successful(Seq.empty))
      when(mockCoreRepo.getDirectChildren(2)).thenReturn(Future.successful(Seq.empty))
      // For grandchildHg tree
      when(mockCoreRepo.getHaplogroupByName("R-L151", HaplogroupType.Y)).thenReturn(Future.successful(Some(grandchildHg)))
      when(mockCoreRepo.getAncestors(3)).thenReturn(Future.successful(Seq(rootHg, childHg)))
      when(mockVariantRepo.getHaplogroupVariants(3)).thenReturn(Future.successful(Seq.empty))
      when(mockCoreRepo.getDirectChildren(3)).thenReturn(Future.successful(Seq.empty))

      whenReady(service.buildTreesFromVariant("M269", HaplogroupType.Y, ApiRoute)) { trees =>
        trees must have size 2
      }
    }

    "findHaplogroupWithVariants returns haplogroup and variants" in {
      when(mockCoreRepo.getHaplogroupByName("R-M269", HaplogroupType.Y)).thenReturn(Future.successful(Some(childHg)))
      when(mockVariantRepo.getHaplogroupVariants(2)).thenReturn(Future.successful(Seq(testVariant)))

      whenReady(service.findHaplogroupWithVariants("R-M269", HaplogroupType.Y)) { case (hgOpt, variants) =>
        hgOpt mustBe defined
        hgOpt.get.name mustBe "R-M269"
        variants must have size 1
      }
    }

    "findHaplogroupWithVariants returns None when haplogroup not found" in {
      when(mockCoreRepo.getHaplogroupByName("NOPE", HaplogroupType.Y)).thenReturn(Future.successful(None))
      when(mockVariantRepo.getHaplogroupVariants(0)).thenReturn(Future.successful(Seq.empty))

      whenReady(service.findHaplogroupWithVariants("NOPE", HaplogroupType.Y)) { case (hgOpt, variants) =>
        hgOpt mustBe None
        variants mustBe empty
      }
    }

    "mapApiResponse flattens tree to subclade sequence" in {
      val zonedNow = now.atZone(ZoneId.systemDefault())
      val child = TreeNodeDTO("R-M269", Seq.empty, List.empty, zonedNow)
      val root = TreeNodeDTO("R", Seq.empty, List(child), zonedNow, isBackbone = true)

      val result = service.mapApiResponse(Some(root))
      result must have size 2
      result.head.name mustBe "R"
      result.head.parentName mustBe None
      result(1).name mustBe "R-M269"
      result(1).parentName mustBe Some("R")
    }

    "mapApiResponse returns empty for None input" in {
      val result = service.mapApiResponse(None)
      result mustBe empty
    }

    "extractCoordinates maps JSONB to GenomicCoordinate map" in {
      when(mockCoreRepo.getHaplogroupByName("R", HaplogroupType.Y)).thenReturn(Future.successful(Some(rootHg)))
      when(mockCoreRepo.getAncestors(1)).thenReturn(Future.successful(Seq.empty))
      when(mockVariantRepo.getHaplogroupVariants(1)).thenReturn(Future.successful(Seq(testVariant)))
      when(mockCoreRepo.getDirectChildren(1)).thenReturn(Future.successful(Seq.empty))

      whenReady(service.buildTreeResponse("R", HaplogroupType.Y, ApiRoute)) { tree =>
        val variant = tree.subclade.get.variants.head
        variant.coordinates must contain key "chrY [b38]"
        val coord = variant.coordinates("chrY [b38]")
        coord.start mustBe 1000
        coord.anc mustBe "A"
        coord.der mustBe "G"
      }
    }

    "extractAliases maps JSONB to alias map" in {
      when(mockCoreRepo.getHaplogroupByName("R", HaplogroupType.Y)).thenReturn(Future.successful(Some(rootHg)))
      when(mockCoreRepo.getAncestors(1)).thenReturn(Future.successful(Seq.empty))
      when(mockVariantRepo.getHaplogroupVariants(1)).thenReturn(Future.successful(Seq(testVariant)))
      when(mockCoreRepo.getDirectChildren(1)).thenReturn(Future.successful(Seq.empty))

      whenReady(service.buildTreeResponse("R", HaplogroupType.Y, ApiRoute)) { tree =>
        val variant = tree.subclade.get.variants.head
        variant.aliases must contain key "rsId"
        variant.aliases("rsId") must contain("rs12345")
        variant.aliases must contain key "commonName"
        variant.aliases("commonName") must contain("M269")
      }
    }

    "isBackbone flag is set correctly based on haplogroup source" in {
      when(mockCoreRepo.getHaplogroupByName("R", HaplogroupType.Y)).thenReturn(Future.successful(Some(rootHg)))
      when(mockCoreRepo.getAncestors(1)).thenReturn(Future.successful(Seq.empty))
      when(mockVariantRepo.getHaplogroupVariants(1)).thenReturn(Future.successful(Seq.empty))
      when(mockCoreRepo.getDirectChildren(1)).thenReturn(Future.successful(Seq(grandchildHg)))
      when(mockVariantRepo.getHaplogroupVariants(3)).thenReturn(Future.successful(Seq.empty))
      when(mockCoreRepo.getDirectChildren(3)).thenReturn(Future.successful(Seq.empty))

      whenReady(service.buildTreeResponse("R", HaplogroupType.Y, ApiRoute)) { tree =>
        tree.subclade.get.isBackbone mustBe true // rootHg source = "backbone"
        tree.subclade.get.children.head.isBackbone mustBe false // grandchildHg source = "community"
      }
    }
  }
}
