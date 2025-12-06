package services

import jakarta.inject.{Inject, Singleton}
import models.api.ExternalBiosampleRequest
import services.firehose.{CitizenBiosampleEvent, CitizenBiosampleEventHandler, FirehoseResult}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

/**
 * Service facade for CitizenBiosample operations.
 *
 * This service wraps REST API requests into FirehoseEvents and delegates
 * to the CitizenBiosampleEventHandler. This pattern allows:
 *
 * - Phase 1: REST API calls go through this facade
 * - Phase 2: Kafka consumer calls the handler directly
 * - Phase 3: Firehose consumer calls the handler directly
 *
 * The facade translates FirehoseResults back to exceptions for
 * backward compatibility with the existing controller error handling.
 */
@Singleton
class CitizenBiosampleService @Inject()(
  eventHandler: CitizenBiosampleEventHandler
)(implicit ec: ExecutionContext) {

  /**
   * Create a new CitizenBiosample from an API request.
   * Wraps the request as a Create event and processes it.
   */
  def createBiosample(request: ExternalBiosampleRequest): Future[UUID] = {
    val event = CitizenBiosampleEvent.forCreate(request)

    eventHandler.handle(event).flatMap {
      case FirehoseResult.Success(_, _, Some(guid), _) =>
        Future.successful(guid)

      case FirehoseResult.Conflict(_, message) =>
        Future.failed(new IllegalArgumentException(message))

      case FirehoseResult.Success(_, _, None, _) =>
        Future.failed(new RuntimeException("Handler did not return GUID"))

      case FirehoseResult.ValidationError(_, message) =>
        Future.failed(new IllegalArgumentException(message))

      case FirehoseResult.Error(_, message, cause) =>
        Future.failed(cause.getOrElse(new RuntimeException(message)))

      case FirehoseResult.NotFound(_) =>
        Future.failed(new NoSuchElementException("Unexpected NotFound on create"))
    }
  }

  /**
   * Update an existing CitizenBiosample.
   * Wraps the request as an Update event and processes it.
   */
  def updateBiosample(atUri: String, request: ExternalBiosampleRequest): Future[UUID] = {
    val event = CitizenBiosampleEvent.forUpdate(atUri, request)

    eventHandler.handle(event).flatMap {
      case FirehoseResult.Success(_, _, Some(guid), _) =>
        Future.successful(guid)

      case FirehoseResult.Success(_, _, None, _) =>
        Future.failed(new RuntimeException("Handler did not return GUID"))

      case FirehoseResult.NotFound(_) =>
        Future.failed(new NoSuchElementException(s"Biosample not found for atUri: $atUri"))

      case FirehoseResult.Conflict(_, message) =>
        Future.failed(new IllegalStateException(message))

      case FirehoseResult.ValidationError(_, message) =>
        Future.failed(new IllegalArgumentException(message))

      case FirehoseResult.Error(_, message, cause) =>
        Future.failed(cause.getOrElse(new RuntimeException(message)))
    }
  }

  /**
   * Soft delete a CitizenBiosample.
   * Wraps as a Delete event and processes it.
   */
  def deleteBiosample(atUri: String): Future[Boolean] = {
    val event = CitizenBiosampleEvent.forDelete(atUri)

    eventHandler.handle(event).map {
      case FirehoseResult.Success(_, _, _, _) => true
      case FirehoseResult.NotFound(_) => false
      case _ => false
    }
  }
}
