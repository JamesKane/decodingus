package controllers

import org.webjars.play.WebJarsUtil

import javax.inject.*
import play.api.*
import play.api.cache.{Cached, SyncCacheApi}
import play.api.mvc.{Action, *}

import scala.concurrent.duration.DurationInt

/**
 * A controller for handling HTTP requests to the application's main pages.
 *
 * This class contains actions for rendering HTML pages for various public-facing
 * sections of the application, such as the homepage, cookie usage policy, privacy
 * policy, terms of service, and public API information.
 *
 * @param controllerComponents provides the base controller components required by all controllers
 * @param webJarsUtil          utility for managing web jar assets
 */
@Singleton
class HomeController @Inject()(val controllerComponents: ControllerComponents,
                               cached: Cached,
                               cache: SyncCacheApi
                              )
                              (using webJarsUtil: WebJarsUtil) extends BaseController {

  /**
   * Create an Action to render an HTML page.
   *
   * The configuration in the `routes` file means that this method
   * will be called when the application receives a `GET` request with
   * a path of `/`.
   */
  def index(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.index())
  }

  /**
   * Renders the Cookie Usage Policy page.
   *
   * This action handles GET requests for the cookie usage policy of the application.
   * It loads and displays the static HTML content detailing the application's current, 
   * future, and potential use of cookies, including compliance with relevant data protection regulations.
   *
   * @return an action that renders the Cookie Usage Policy view as an HTML response
   */
  def cookieUsage(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.cookies())
  }

  /**
   * Renders the Privacy Policy page.
   *
   * This action handles GET requests for the privacy policy of the application.
   * It loads and displays a static HTML page outlining the application's policies
   * regarding data privacy and protection.
   *
   * @return an action that renders the Privacy Policy view as an HTML response
   */
  def privacy(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.privacyPolicy())
  }

  /**
   * Renders the Terms of Use page.
   *
   * This action handles GET requests for the Terms of Use of the application.
   * It loads and displays the static HTML content detailing the application's terms and conditions
   * for using the website and its features.
   *
   * @return an action that renders the Terms of Use view as an HTML response
   */
  def terms(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.terms())
  }

  /**
   * Handles GET requests to render the Public API documentation.
   *
   * This action loads and displays a static HTML page that provides documentation
   * for the application's public API endpoints, including details about available routes,
   * their responses, and formats.
   *
   * @return an action that renders the Public API documentation as an HTML response
   */
  def publicApi(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.publicApi())
  }

  /**
   * Renders the FAQ (Frequently Asked Questions) page.
   *
   * This action handles GET requests to display the FAQ section of the application.
   * It loads and renders a static HTML view containing common questions and answers
   * related to the application, its features, or its usage.
   *
   * @return an action that renders the FAQ view as an HTML response
   */
  def faq(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.faq())
  }

  /**
   * Generates a sitemap in XML format for the application, listing all static URLs.
   * The sitemap is cached for 24 hours and adheres to the standard sitemap XML schema.
   *
   * @return an `EssentialAction` that produces the sitemap XML response with a 200 status code and
   *         `application/xml` content type.
   */
  def sitemap(): EssentialAction = cached.status(_ => "sitemap", 200, 24.hours) {
    Action { implicit request =>
      val baseUrl = "https://decoding-us.com"

      val staticUrls = List(
        "/",
        "/cookie-usage",
        "/terms",
        "/privacy",
        "/public-api",
        "/faq",
        "/ytree",
        "/mtree",
        "/references",
        "/coverage-benchmarks",
        "/contact"
      )

      val xmlContent =
        """<?xml version="1.0" encoding="UTF-8"?>
          |<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
          |""".stripMargin +
          staticUrls.map { url =>
            s"""  <url>
               |    <loc>$baseUrl$url</loc>
               |    <changefreq>weekly</changefreq>
               |    <priority>0.8</priority>
               |  </url>""".stripMargin
          }.mkString("\n") +
          "\n</urlset>"

      Ok(xmlContent).as("application/xml")
    }
  }

  /**
   * Handles requests for the "robots.txt" file.
   *
   * This action generates and serves a static robots.txt file with instructions for web crawlers,
   * allowing all user agents to crawl the site and providing the location of the sitemap.
   *
   * The response is cached for 24 hours and has a 200 OK status code with a content type of text/plain.
   *
   * @return an `EssentialAction` that produces the robots.txt file as a text/plain response.
   */
  def robots(): EssentialAction = cached.status(_ => "robots", 200, 24.hours) {
    Action { implicit request =>
      Ok(
        """User-agent: *
          |Allow: /
          |
          |Sitemap: https://decoding-us.com/sitemap.xml""".stripMargin
      ).as("text/plain")
    }
  }
}
