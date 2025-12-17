package controllers

import actions.ApiSecurityAction
import models.HaplogroupType
import models.api.haplogroups.*
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{reset, verify, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.mvc.Results
import play.api.test.Helpers.*
import play.api.test.{FakeRequest, Injecting}
import services.HaplogroupTreeMergeService

import scala.concurrent.{ExecutionContext, Future}

class HaplogroupTreeMergeControllerSpec extends PlaySpec
  with GuiceOneAppPerSuite
  with Injecting
  with MockitoSugar
  with ScalaFutures
  with BeforeAndAfterEach {

  // Mock service
  val mockMergeService: HaplogroupTreeMergeService = mock[HaplogroupTreeMergeService]

  override def fakeApplication(): Application = {
    new GuiceApplicationBuilder()
      .configure(
        "play.evolutions.enabled" -> false,
        "api.key.enabled" -> false // Disable API key for testing
      )
      .overrides(
        bind[HaplogroupTreeMergeService].toInstance(mockMergeService)
      )
      .build()
  }

  override def beforeEach(): Unit = {
    reset(mockMergeService)
  }

  // Test fixtures
  def createSuccessResponse(nodesCreated: Int = 5): TreeMergeResponse = TreeMergeResponse(
    success = true,
    message = "Merge completed successfully",
    statistics = MergeStatistics(
      nodesProcessed = 10,
      nodesCreated = nodesCreated,
      nodesUpdated = 3,
      nodesUnchanged = 2,
      variantsAdded = 20,
      variantsUpdated = 5,
      relationshipsCreated = 4,
      relationshipsUpdated = 1,
      splitOperations = 0
    )
  )

  def createPreviewResponse(): MergePreviewResponse = MergePreviewResponse(
    statistics = MergeStatistics(10, 5, 3, 2, 20, 5, 4, 1, 0),
    conflicts = List.empty,
    splits = List.empty,
    ambiguities = List.empty,
    newNodes = List("NewNode1", "NewNode2"),
    updatedNodes = List("UpdatedNode1"),
    unchangedNodes = List("UnchangedNode1")
  )

  "HaplogroupTreeMergeController" should {

    // =========================================================================
    // mergeFullTree endpoint tests
    // =========================================================================

    "return 202 Accepted for full tree merge request" in {
      when(mockMergeService.mergeFullTree(any[TreeMergeRequest]))
        .thenReturn(Future.successful(createSuccessResponse()))

      val requestBody = Json.obj(
        "haplogroupType" -> "Y",
        "sourceTree" -> Json.obj(
          "name" -> "R1b",
          "variants" -> Json.arr(Json.obj("name" -> "M269"))
        ),
        "sourceName" -> "ytree.net"
      )

      val request = FakeRequest(POST, "/api/v1/manage/haplogroups/merge")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(requestBody)

      val result = route(app, request).get

      status(result) mustBe ACCEPTED
      contentType(result) mustBe Some("application/json")

      val json = contentAsJson(result)
      (json \ "status").as[String] mustBe "Processing"
    }

    "return 202 Accepted even for failed merge (fire-and-forget)" in {
      // With fire-and-forget pattern, the controller always returns 202 immediately
      // Errors are logged in the background, not returned to the client
      val failureResponse = TreeMergeResponse.failure(
        "Merge validation failed",
        List("Invalid tree structure")
      )
      when(mockMergeService.mergeFullTree(any[TreeMergeRequest]))
        .thenReturn(Future.successful(failureResponse))

      val requestBody = Json.obj(
        "haplogroupType" -> "Y",
        "sourceTree" -> Json.obj("name" -> "Invalid"),
        "sourceName" -> "test"
      )

      val request = FakeRequest(POST, "/api/v1/manage/haplogroups/merge")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(requestBody)

      val result = route(app, request).get

      status(result) mustBe ACCEPTED
      val json = contentAsJson(result)
      (json \ "status").as[String] mustBe "Processing"
    }

    "reject invalid haplogroup type in JSON body" in {
      val requestBody = Json.obj(
        "haplogroupType" -> "INVALID_TYPE",
        "sourceTree" -> Json.obj("name" -> "Test"),
        "sourceName" -> "test"
      )

      val request = FakeRequest(POST, "/api/v1/manage/haplogroups/merge")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(requestBody)

      // The JSON parsing throws an exception for invalid HaplogroupType
      // which propagates through Play's JSON body parser
      an[IllegalArgumentException] must be thrownBy {
        val result = route(app, request).get
        status(result)
      }
    }

    "return 400 for missing required fields" in {
      val requestBody = Json.obj(
        "haplogroupType" -> "Y"
        // Missing sourceTree and sourceName
      )

      val request = FakeRequest(POST, "/api/v1/manage/haplogroups/merge")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(requestBody)

      val result = route(app, request).get

      status(result) mustBe BAD_REQUEST
    }

    "return 202 Accepted even for service exceptions (fire-and-forget)" in {
      // With fire-and-forget pattern, exceptions are logged in the background
      // The client still receives 202 Accepted immediately
      when(mockMergeService.mergeFullTree(any[TreeMergeRequest]))
        .thenReturn(Future.failed(new RuntimeException("Database connection failed")))

      val requestBody = Json.obj(
        "haplogroupType" -> "Y",
        "sourceTree" -> Json.obj("name" -> "Test"),
        "sourceName" -> "test"
      )

      val request = FakeRequest(POST, "/api/v1/manage/haplogroups/merge")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(requestBody)

      val result = route(app, request).get

      status(result) mustBe ACCEPTED
      val json = contentAsJson(result)
      (json \ "status").as[String] mustBe "Processing"
    }

    "pass through all request parameters to service" in {
      when(mockMergeService.mergeFullTree(any[TreeMergeRequest]))
        .thenReturn(Future.successful(createSuccessResponse()))

      val requestBody = Json.obj(
        "haplogroupType" -> "Y",
        "sourceTree" -> Json.obj(
          "name" -> "R1b",
          "variants" -> Json.arr(Json.obj("name" -> "M269")),
          "formedYbp" -> 4500
        ),
        "sourceName" -> "ytree.net",
        "priorityConfig" -> Json.obj(
          "sourcePriorities" -> Json.arr("ytree.net", "ISOGG")
        ),
        "conflictStrategy" -> "higher_priority_wins",
        "dryRun" -> true
      )

      val request = FakeRequest(POST, "/api/v1/manage/haplogroups/merge")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(requestBody)

      val result = route(app, request).get

      status(result) mustBe ACCEPTED
      verify(mockMergeService).mergeFullTree(any[TreeMergeRequest])
    }

    // =========================================================================
    // mergeSubtree endpoint tests
    // =========================================================================

    "return 202 Accepted for subtree merge request" in {
      when(mockMergeService.mergeSubtree(any[SubtreeMergeRequest]))
        .thenReturn(Future.successful(createSuccessResponse()))

      val requestBody = Json.obj(
        "haplogroupType" -> "Y",
        "anchorHaplogroupName" -> "R1b",
        "sourceTree" -> Json.obj(
          "name" -> "R1b-L21",
          "variants" -> Json.arr(Json.obj("name" -> "L21"))
        ),
        "sourceName" -> "ytree.net"
      )

      val request = FakeRequest(POST, "/api/v1/manage/haplogroups/merge/subtree")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(requestBody)

      val result = route(app, request).get

      status(result) mustBe ACCEPTED
      val json = contentAsJson(result)
      (json \ "status").as[String] mustBe "Processing"
    }

    "return 202 Accepted even when anchor haplogroup error occurs (fire-and-forget)" in {
      // With fire-and-forget pattern, validation errors occur in the background
      // The client still receives 202 Accepted immediately
      when(mockMergeService.mergeSubtree(any[SubtreeMergeRequest]))
        .thenReturn(Future.failed(new IllegalArgumentException("Anchor haplogroup 'NONEXISTENT' not found")))

      val requestBody = Json.obj(
        "haplogroupType" -> "Y",
        "anchorHaplogroupName" -> "NONEXISTENT",
        "sourceTree" -> Json.obj("name" -> "Test"),
        "sourceName" -> "test"
      )

      val request = FakeRequest(POST, "/api/v1/manage/haplogroups/merge/subtree")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(requestBody)

      val result = route(app, request).get

      status(result) mustBe ACCEPTED
      val json = contentAsJson(result)
      (json \ "status").as[String] mustBe "Processing"
    }

    "return 400 for missing anchorHaplogroupName" in {
      val requestBody = Json.obj(
        "haplogroupType" -> "Y",
        // Missing anchorHaplogroupName
        "sourceTree" -> Json.obj("name" -> "Test"),
        "sourceName" -> "test"
      )

      val request = FakeRequest(POST, "/api/v1/manage/haplogroups/merge/subtree")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(requestBody)

      val result = route(app, request).get

      status(result) mustBe BAD_REQUEST
    }

    // =========================================================================
    // previewMerge endpoint tests
    // =========================================================================

    "return 200 for preview request" in {
      when(mockMergeService.previewMerge(any[MergePreviewRequest]))
        .thenReturn(Future.successful(createPreviewResponse()))

      val requestBody = Json.obj(
        "haplogroupType" -> "Y",
        "sourceTree" -> Json.obj(
          "name" -> "R1b",
          "variants" -> Json.arr(Json.obj("name" -> "M269"))
        ),
        "sourceName" -> "ytree.net"
      )

      val request = FakeRequest(POST, "/api/v1/manage/haplogroups/merge/preview")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(requestBody)

      val result = route(app, request).get

      status(result) mustBe OK
      val json = contentAsJson(result)
      (json \ "newNodes").as[List[String]] must contain("NewNode1")
      (json \ "statistics" \ "nodesProcessed").as[Int] mustBe 10
    }

    "return preview with conflicts" in {
      val previewWithConflicts = MergePreviewResponse(
        statistics = MergeStatistics(10, 5, 3, 2, 20, 5, 4, 1, 0),
        conflicts = List(
          MergeConflict(
            haplogroupName = "R1b-L21",
            field = "formedYbp",
            existingValue = "4500",
            newValue = "4800",
            resolution = "will_update",
            existingSource = "ISOGG",
            newSource = "ytree.net"
          )
        ),
        splits = List.empty,
        ambiguities = List.empty,
        newNodes = List.empty,
        updatedNodes = List("R1b-L21"),
        unchangedNodes = List.empty
      )

      when(mockMergeService.previewMerge(any[MergePreviewRequest]))
        .thenReturn(Future.successful(previewWithConflicts))

      val requestBody = Json.obj(
        "haplogroupType" -> "Y",
        "sourceTree" -> Json.obj("name" -> "R1b-L21", "formedYbp" -> 4800),
        "sourceName" -> "ytree.net"
      )

      val request = FakeRequest(POST, "/api/v1/manage/haplogroups/merge/preview")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(requestBody)

      val result = route(app, request).get

      status(result) mustBe OK
      val json = contentAsJson(result)
      (json \ "conflicts").as[List[MergeConflict]] must have size 1
      (json \ "conflicts" \ 0 \ "field").as[String] mustBe "formedYbp"
    }

    "accept preview with optional anchor" in {
      when(mockMergeService.previewMerge(any[MergePreviewRequest]))
        .thenReturn(Future.successful(createPreviewResponse()))

      val requestBody = Json.obj(
        "haplogroupType" -> "Y",
        "anchorHaplogroupName" -> "R1b",
        "sourceTree" -> Json.obj("name" -> "R1b-L21"),
        "sourceName" -> "ytree.net"
      )

      val request = FakeRequest(POST, "/api/v1/manage/haplogroups/merge/preview")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(requestBody)

      val result = route(app, request).get

      status(result) mustBe OK
    }

    "handle preview service exceptions" in {
      when(mockMergeService.previewMerge(any[MergePreviewRequest]))
        .thenReturn(Future.failed(new RuntimeException("Index build failed")))

      val requestBody = Json.obj(
        "haplogroupType" -> "Y",
        "sourceTree" -> Json.obj("name" -> "Test"),
        "sourceName" -> "test"
      )

      val request = FakeRequest(POST, "/api/v1/manage/haplogroups/merge/preview")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(requestBody)

      val result = route(app, request).get

      status(result) mustBe INTERNAL_SERVER_ERROR
    }

    // =========================================================================
    // MT DNA tests
    // =========================================================================

    "handle MT DNA haplogroup type" in {
      when(mockMergeService.mergeFullTree(any[TreeMergeRequest]))
        .thenReturn(Future.successful(createSuccessResponse()))

      val requestBody = Json.obj(
        "haplogroupType" -> "MT",
        "sourceTree" -> Json.obj(
          "name" -> "H1",
          "variants" -> Json.arr(Json.obj("name" -> "H1-defining"))
        ),
        "sourceName" -> "mtDNA-tree"
      )

      val request = FakeRequest(POST, "/api/v1/manage/haplogroups/merge")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(requestBody)

      val result = route(app, request).get

      status(result) mustBe ACCEPTED
    }

    // =========================================================================
    // Complex tree structure tests
    // =========================================================================

    "handle deeply nested tree in request" in {
      when(mockMergeService.mergeFullTree(any[TreeMergeRequest]))
        .thenReturn(Future.successful(createSuccessResponse(nodesCreated = 10)))

      val requestBody = Json.obj(
        "haplogroupType" -> "Y",
        "sourceTree" -> Json.obj(
          "name" -> "R1b",
          "variants" -> Json.arr(Json.obj("name" -> "M269")),
          "children" -> Json.arr(
            Json.obj(
              "name" -> "R1b-L21",
              "variants" -> Json.arr(Json.obj("name" -> "L21")),
              "children" -> Json.arr(
                Json.obj(
                  "name" -> "R1b-DF13",
                  "variants" -> Json.arr(Json.obj("name" -> "DF13")),
                  "children" -> Json.arr(
                    Json.obj(
                      "name" -> "R1b-Z39589",
                      "variants" -> Json.arr(Json.obj("name" -> "Z39589"))
                    )
                  )
                )
              )
            )
          )
        ),
        "sourceName" -> "ytree.net"
      )

      val request = FakeRequest(POST, "/api/v1/manage/haplogroups/merge")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(requestBody)

      val result = route(app, request).get

      status(result) mustBe ACCEPTED
    }

    // =========================================================================
    // Dry run tests
    // =========================================================================

    "handle dry run request" in {
      when(mockMergeService.mergeFullTree(any[TreeMergeRequest]))
        .thenReturn(Future.successful(createSuccessResponse()))

      val requestBody = Json.obj(
        "haplogroupType" -> "Y",
        "sourceTree" -> Json.obj("name" -> "Test"),
        "sourceName" -> "test",
        "dryRun" -> true
      )

      val request = FakeRequest(POST, "/api/v1/manage/haplogroups/merge")
        .withHeaders("Content-Type" -> "application/json")
        .withJsonBody(requestBody)

      val result = route(app, request).get

      status(result) mustBe ACCEPTED
    }

    // =========================================================================
    // Content-Type tests
    // =========================================================================

    "return 415 for non-JSON content type" in {
      val request = FakeRequest(POST, "/api/v1/manage/haplogroups/merge")
        .withHeaders("Content-Type" -> "text/plain")
        .withBody("not json")

      val result = route(app, request).get

      status(result) mustBe UNSUPPORTED_MEDIA_TYPE
    }

    "return 400 for malformed JSON" in {
      val request = FakeRequest(POST, "/api/v1/manage/haplogroups/merge")
        .withHeaders("Content-Type" -> "application/json")
        .withBody("{invalid json")

      val result = route(app, request).get

      status(result) mustBe BAD_REQUEST
    }
  }
}
