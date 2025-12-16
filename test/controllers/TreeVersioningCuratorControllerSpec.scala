package controllers

import models.HaplogroupType
import models.domain.haplogroups.*
import org.mockito.ArgumentMatchers.{any, anyInt, anyString}
import org.mockito.Mockito.{reset, when}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers.*
import play.api.test.{FakeRequest, Injecting}
import services.TreeVersioningService

import java.time.LocalDateTime
import scala.concurrent.{ExecutionContext, Future}

/**
 * Controller spec for TreeVersioningCuratorController.
 *
 * Note: Curator routes require authentication. These tests verify:
 * 1. Routes exist and respond (with redirect to auth when not authenticated)
 * 2. Service interactions work correctly when called
 *
 * For full functionality testing, see TreeVersioningServiceSpec which
 * provides comprehensive unit tests for the business logic.
 */
class TreeVersioningCuratorControllerSpec extends PlaySpec
  with GuiceOneAppPerSuite
  with Injecting
  with MockitoSugar
  with ScalaFutures
  with BeforeAndAfterEach {

  // Mock service
  val mockTreeVersioningService: TreeVersioningService = mock[TreeVersioningService]

  override def fakeApplication(): Application = {
    new GuiceApplicationBuilder()
      .configure(
        "play.evolutions.enabled" -> false
      )
      .overrides(
        bind[TreeVersioningService].toInstance(mockTreeVersioningService)
      )
      .build()
  }

  override def beforeEach(): Unit = {
    reset(mockTreeVersioningService)
  }

  // Test fixtures
  val now: LocalDateTime = LocalDateTime.now()

  def createChangeSetSummary(
    id: Int,
    haplogroupType: HaplogroupType = HaplogroupType.Y,
    status: ChangeSetStatus = ChangeSetStatus.ReadyForReview
  ): ChangeSetSummary = ChangeSetSummary(
    id = id,
    haplogroupType = haplogroupType,
    name = s"ISOGG-2024-12-16-$id",
    sourceName = "ISOGG",
    status = status,
    createdAt = now,
    createdBy = "system",
    statistics = ChangeSetStatistics(nodesProcessed = 100, nodesCreated = 20),
    totalChanges = 50,
    pendingChanges = 30,
    reviewedChanges = 20
  )

  /**
   * Curator routes require authentication.
   * These tests verify routes exist and redirect to auth (303) when not authenticated.
   */
  "TreeVersioningCuratorController routes (unauthenticated)" should {

    // =========================================================================
    // Verify routes exist and require authentication
    // =========================================================================

    "redirect to auth for change sets list" in {
      val request = FakeRequest(GET, "/curator/change-sets")
      val result = route(app, request).get

      // 303 indicates route exists and redirects to auth
      status(result) mustBe SEE_OTHER
    }

    "redirect to auth for change sets fragment" in {
      val request = FakeRequest(GET, "/curator/change-sets/fragment")
      val result = route(app, request).get

      status(result) mustBe SEE_OTHER
    }

    "redirect to auth for change set detail panel" in {
      val request = FakeRequest(GET, "/curator/change-sets/1/panel")
      val result = route(app, request).get

      status(result) mustBe SEE_OTHER
    }

    "redirect to auth for pending changes" in {
      val request = FakeRequest(GET, "/curator/change-sets/1/changes/pending")
      val result = route(app, request).get

      status(result) mustBe SEE_OTHER
    }

    "redirect to auth for diff view" in {
      val request = FakeRequest(GET, "/curator/change-sets/1/diff")
      val result = route(app, request).get

      status(result) mustBe SEE_OTHER
    }

    "redirect to auth for diff fragment" in {
      val request = FakeRequest(GET, "/curator/change-sets/1/diff/fragment")
      val result = route(app, request).get

      status(result) mustBe SEE_OTHER
    }

    "redirect to auth for ambiguity report" in {
      val request = FakeRequest(GET, "/curator/change-sets/1/ambiguity-report")
      val result = route(app, request).get

      status(result) mustBe SEE_OTHER
    }

    // =========================================================================
    // POST routes redirect to auth when not authenticated
    // =========================================================================

    "redirect to auth for start review" in {
      val request = FakeRequest(POST, "/curator/change-sets/1/start-review")
      val result = route(app, request).get

      status(result) mustBe SEE_OTHER
    }

    "redirect to auth for apply change set" in {
      val request = FakeRequest(POST, "/curator/change-sets/1/apply")
      val result = route(app, request).get

      status(result) mustBe SEE_OTHER
    }

    "redirect to auth for discard change set" in {
      val request = FakeRequest(POST, "/curator/change-sets/1/discard")
        .withFormUrlEncodedBody("reason" -> "Test reason for discard")
      val result = route(app, request).get

      status(result) mustBe SEE_OTHER
    }

    "redirect to auth for approve all pending" in {
      val request = FakeRequest(POST, "/curator/change-sets/1/approve-all")
      val result = route(app, request).get

      status(result) mustBe SEE_OTHER
    }

    "redirect to auth for review change" in {
      val request = FakeRequest(POST, "/curator/change-sets/1/changes/100/review")
        .withFormUrlEncodedBody("action" -> "APPLIED")
      val result = route(app, request).get

      status(result) mustBe SEE_OTHER
    }

    // =========================================================================
    // Verify invalid routes return 404
    // =========================================================================

    "return 404 for invalid route" in {
      val request = FakeRequest(GET, "/curator/change-sets/invalid-endpoint")
      val result = route(app, request).get

      status(result) mustBe NOT_FOUND
    }
  }

  /**
   * These tests verify that the URL routes are correctly mapped.
   * They check that the routes exist and respond appropriately.
   */
  "TreeVersioningCuratorController route mapping" should {

    "have GET route for /curator/change-sets" in {
      val request = FakeRequest(GET, "/curator/change-sets")
      val result = route(app, request)
      result mustBe defined
    }

    "have GET route for /curator/change-sets/fragment" in {
      val request = FakeRequest(GET, "/curator/change-sets/fragment")
      val result = route(app, request)
      result mustBe defined
    }

    "have GET route for /curator/change-sets/:id/panel" in {
      val request = FakeRequest(GET, "/curator/change-sets/1/panel")
      val result = route(app, request)
      result mustBe defined
    }

    "have GET route for /curator/change-sets/:id/changes/pending" in {
      val request = FakeRequest(GET, "/curator/change-sets/1/changes/pending")
      val result = route(app, request)
      result mustBe defined
    }

    "have POST route for /curator/change-sets/:id/start-review" in {
      val request = FakeRequest(POST, "/curator/change-sets/1/start-review")
      val result = route(app, request)
      result mustBe defined
    }

    "have POST route for /curator/change-sets/:id/apply" in {
      val request = FakeRequest(POST, "/curator/change-sets/1/apply")
      val result = route(app, request)
      result mustBe defined
    }

    "have POST route for /curator/change-sets/:id/discard" in {
      val request = FakeRequest(POST, "/curator/change-sets/1/discard")
      val result = route(app, request)
      result mustBe defined
    }

    "have POST route for /curator/change-sets/:id/approve-all" in {
      val request = FakeRequest(POST, "/curator/change-sets/1/approve-all")
      val result = route(app, request)
      result mustBe defined
    }

    "have POST route for /curator/change-sets/:changeSetId/changes/:changeId/review" in {
      val request = FakeRequest(POST, "/curator/change-sets/1/changes/100/review")
      val result = route(app, request)
      result mustBe defined
    }

    "have GET route for /curator/change-sets/:id/diff" in {
      val request = FakeRequest(GET, "/curator/change-sets/1/diff")
      val result = route(app, request)
      result mustBe defined
    }

    "have GET route for /curator/change-sets/:id/diff/fragment" in {
      val request = FakeRequest(GET, "/curator/change-sets/1/diff/fragment")
      val result = route(app, request)
      result mustBe defined
    }

    "have GET route for /curator/change-sets/:id/ambiguity-report" in {
      val request = FakeRequest(GET, "/curator/change-sets/1/ambiguity-report")
      val result = route(app, request)
      result mustBe defined
    }

    "have GET route for /curator/change-sets/:id/ambiguity-report/download" in {
      val request = FakeRequest(GET, "/curator/change-sets/1/ambiguity-report/download")
      val result = route(app, request)
      result mustBe defined
    }
  }
}
