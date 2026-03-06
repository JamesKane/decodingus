package services

import helpers.ServiceSpec
import models.HaplogroupType
import models.atmosphere.VariantCall
import models.domain.discovery.*
import models.domain.genomics.{MutationType, NamingStatus, VariantV2}
import models.domain.haplogroups.Haplogroup
import org.mockito.ArgumentMatchers.{any, anyInt, anyString}
import org.mockito.Mockito.{never, reset, verify, when}
import play.api.libs.json.Json
import repositories.{HaplogroupCoreRepository, HaplogroupVariantRepository, PrivateVariantRepository}

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.Future

class PrivateVariantExtractionServiceSpec extends ServiceSpec {

  val mockPrivateVariantRepo: PrivateVariantRepository = mock[PrivateVariantRepository]
  val mockVariantRepo: HaplogroupVariantRepository = mock[HaplogroupVariantRepository]
  val mockCoreRepo: HaplogroupCoreRepository = mock[HaplogroupCoreRepository]

  val service = new PrivateVariantExtractionService(mockPrivateVariantRepo, mockVariantRepo, mockCoreRepo)

  override def beforeEach(): Unit = {
    reset(mockPrivateVariantRepo, mockVariantRepo, mockCoreRepo)
  }

  val now: LocalDateTime = LocalDateTime.of(2025, 1, 1, 0, 0)
  val sampleGuid: UUID = UUID.randomUUID()

  val terminalHg: Haplogroup = Haplogroup(
    id = Some(100), name = "R-M269", lineage = Some("R>R-M269"),
    description = None, haplogroupType = HaplogroupType.Y,
    revisionId = 1, source = "backbone", confidenceLevel = "high",
    validFrom = now, validUntil = None
  )

  val ancestorHg: Haplogroup = Haplogroup(
    id = Some(1), name = "R", lineage = Some("R"),
    description = None, haplogroupType = HaplogroupType.Y,
    revisionId = 1, source = "backbone", confidenceLevel = "high",
    validFrom = now, validUntil = None
  )

  // A tree variant already on the lineage (should NOT be counted as private)
  val treeVariant: VariantV2 = VariantV2(
    variantId = Some(10), canonicalName = Some("M269"), mutationType = MutationType.SNP,
    coordinates = Json.obj("GRCh38" -> Json.obj("contig" -> "chrY", "position" -> 22103211, "ref" -> "G", "alt" -> "A"))
  )

  // A variant not on the tree (should be counted as private)
  val novelVariant: VariantV2 = VariantV2(
    variantId = Some(50), canonicalName = Some("Z1234"), mutationType = MutationType.SNP,
    coordinates = Json.obj("GRCh38" -> Json.obj("contig" -> "chrY", "position" -> 5000000, "ref" -> "C", "alt" -> "T"))
  )

  def makeCall(contig: String, pos: Int, ref: String, alt: String, name: Option[String] = None): VariantCall =
    VariantCall(contig, pos, ref, alt, rsId = None, variantName = name, genotype = None, quality = None, depth = None)

  "PrivateVariantExtractionService" should {

    "return empty when no variant calls provided" in {
      whenReady(service.extractFromExternalBiosample(1, sampleGuid, "R-M269", HaplogroupType.Y, Seq.empty)) { result =>
        result mustBe empty
      }
    }

    "extract private variants filtering out tree variants" in {
      val treeCall = makeCall("chrY", 22103211, "G", "A", Some("M269"))
      val privateCall = makeCall("chrY", 5000000, "C", "T", Some("Z1234"))

      when(mockCoreRepo.getHaplogroupByName("R-M269", HaplogroupType.Y)).thenReturn(Future.successful(Some(terminalHg)))
      when(mockCoreRepo.getAncestors(100)).thenReturn(Future.successful(Seq(ancestorHg)))

      // Tree variants for ancestor R and terminal R-M269
      when(mockVariantRepo.getHaplogroupVariants(1)).thenReturn(Future.successful(Seq.empty))
      when(mockVariantRepo.getHaplogroupVariants(100)).thenReturn(Future.successful(Seq(treeVariant)))

      // Variant resolution
      when(mockVariantRepo.findVariants("M269")).thenReturn(Future.successful(Seq(treeVariant)))
      when(mockVariantRepo.findVariants("Z1234")).thenReturn(Future.successful(Seq(novelVariant)))

      // Save
      when(mockPrivateVariantRepo.createAll(any[Seq[BiosamplePrivateVariant]])).thenAnswer { invocation =>
        val pvs = invocation.getArgument[Seq[BiosamplePrivateVariant]](0)
        Future.successful(pvs.zipWithIndex.map { case (pv, i) => pv.copy(id = Some(i + 1)) })
      }

      whenReady(service.extractFromExternalBiosample(1, sampleGuid, "R-M269", HaplogroupType.Y, Seq(treeCall, privateCall))) { result =>
        result must have size 1
        result.head.variantId mustBe 50
        result.head.sampleType mustBe BiosampleSourceType.External
        result.head.terminalHaplogroupId mustBe 100
      }
    }

    "extract from citizen biosample with correct source type" in {
      val privateCall = makeCall("chrY", 5000000, "C", "T", Some("Z1234"))

      when(mockCoreRepo.getHaplogroupByName("R-M269", HaplogroupType.Y)).thenReturn(Future.successful(Some(terminalHg)))
      when(mockCoreRepo.getAncestors(100)).thenReturn(Future.successful(Seq(ancestorHg)))
      when(mockVariantRepo.getHaplogroupVariants(1)).thenReturn(Future.successful(Seq.empty))
      when(mockVariantRepo.getHaplogroupVariants(100)).thenReturn(Future.successful(Seq.empty))
      when(mockVariantRepo.findVariants("Z1234")).thenReturn(Future.successful(Seq(novelVariant)))
      when(mockPrivateVariantRepo.createAll(any[Seq[BiosamplePrivateVariant]])).thenAnswer { invocation =>
        val pvs = invocation.getArgument[Seq[BiosamplePrivateVariant]](0)
        Future.successful(pvs.zipWithIndex.map { case (pv, i) => pv.copy(id = Some(i + 1)) })
      }

      whenReady(service.extractFromCitizenBiosample(42, sampleGuid, "R-M269", HaplogroupType.Y, Seq(privateCall))) { result =>
        result must have size 1
        result.head.sampleType mustBe BiosampleSourceType.Citizen
        result.head.sampleId mustBe 42
      }
    }

    "fail when terminal haplogroup not found" in {
      when(mockCoreRepo.getHaplogroupByName("UNKNOWN", HaplogroupType.Y)).thenReturn(Future.successful(None))

      val call = makeCall("chrY", 1000, "A", "G")

      whenReady(service.extractFromExternalBiosample(1, sampleGuid, "UNKNOWN", HaplogroupType.Y, Seq(call)).failed) { ex =>
        ex mustBe a[IllegalArgumentException]
        ex.getMessage must include("not found")
      }
    }

    "skip variants not in variant_v2 (resolved to -1)" in {
      val unknownCall = makeCall("chrY", 9999999, "A", "G")

      when(mockCoreRepo.getHaplogroupByName("R-M269", HaplogroupType.Y)).thenReturn(Future.successful(Some(terminalHg)))
      when(mockCoreRepo.getAncestors(100)).thenReturn(Future.successful(Seq.empty))
      when(mockVariantRepo.getHaplogroupVariants(100)).thenReturn(Future.successful(Seq.empty))
      // Position search returns nothing -> resolves to -1
      when(mockVariantRepo.findVariants("chrY:9999999")).thenReturn(Future.successful(Seq.empty))

      // -1 variant ID won't match any tree position so it passes the tree filter,
      // but we still save it (the -1 sentinel is a temporary approach)
      when(mockPrivateVariantRepo.createAll(any[Seq[BiosamplePrivateVariant]])).thenAnswer { invocation =>
        val pvs = invocation.getArgument[Seq[BiosamplePrivateVariant]](0)
        Future.successful(pvs.zipWithIndex.map { case (pv, i) => pv.copy(id = Some(i + 1)) })
      }

      whenReady(service.extractFromExternalBiosample(1, sampleGuid, "R-M269", HaplogroupType.Y, Seq(unknownCall))) { result =>
        result must have size 1
        result.head.variantId mustBe -1
      }
    }

    "not save when all variants are already on tree" in {
      val treeCall = makeCall("chrY", 22103211, "G", "A", Some("M269"))

      when(mockCoreRepo.getHaplogroupByName("R-M269", HaplogroupType.Y)).thenReturn(Future.successful(Some(terminalHg)))
      when(mockCoreRepo.getAncestors(100)).thenReturn(Future.successful(Seq.empty))
      when(mockVariantRepo.getHaplogroupVariants(100)).thenReturn(Future.successful(Seq(treeVariant)))
      when(mockVariantRepo.findVariants("M269")).thenReturn(Future.successful(Seq(treeVariant)))

      whenReady(service.extractFromExternalBiosample(1, sampleGuid, "R-M269", HaplogroupType.Y, Seq(treeCall))) { result =>
        result mustBe empty
        verify(mockPrivateVariantRepo, never()).createAll(any[Seq[BiosamplePrivateVariant]])
      }
    }
  }
}
