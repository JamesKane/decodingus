package helpers

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.mockito.MockitoSugar
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder

import scala.concurrent.ExecutionContext

/**
 * Base trait for service unit tests with mocked dependencies.
 * Provides implicit ExecutionContext and ScalaFutures patience config.
 */
trait ServiceSpec extends PlaySpec with MockitoSugar with ScalaFutures with BeforeAndAfterEach {
  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit val patience: PatienceConfig = PatienceConfig(
    timeout = Span(5, Seconds),
    interval = Span(100, Millis)
  )
}

/**
 * Base trait for controller integration tests.
 * Boots a Play application with H2 in-memory database and disabled startup services.
 * Subclasses should override `additionalOverrides` to bind mock services.
 */
trait ControllerSpec extends PlaySpec
  with GuiceOneAppPerSuite
  with MockitoSugar
  with ScalaFutures
  with BeforeAndAfterEach {

  implicit val patience: PatienceConfig = PatienceConfig(
    timeout = Span(5, Seconds),
    interval = Span(100, Millis)
  )

  /**
   * Override this in subclasses to provide additional Guice bindings (e.g., mock services).
   */
  protected def additionalConfig: Map[String, Any] = Map.empty
  protected def additionalOverrides: Seq[play.api.inject.Binding[?]] = Seq.empty

  override def fakeApplication(): Application = {
    val builder = new GuiceApplicationBuilder()
      .configure(
        Map(
          "config.resource" -> "application.test.conf",
          "api.key.enabled" -> false
        ) ++ additionalConfig
      )

    additionalOverrides.foldLeft(builder) { (b, binding) =>
      b.overrides(binding)
    }.build()
  }
}
