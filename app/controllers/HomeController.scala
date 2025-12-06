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
   * Generates and serves an XML sitemap for the application. The sitemap includes
   * a list of predefined static routes that correspond to important pages, such as
   * the homepage, cookie usage, terms, privacy policy, public API documentation,
   * FAQ, and other significant sections of the website.
   *
   * The response is an XML document compliant with the sitemap protocol, which
   * includes details like the URL location, change frequency, and priority for
   * each page. It helps search engines effectively crawl and index the website's
   * content. The generated sitemap is cached for 24 hours for performance optimization.
   *
   * @return an `EssentialAction` that produces the generated sitemap as an XML response with a "200 OK" status.
   */
  def sitemap(): EssentialAction = cached.status(_ => "sitemap", 200, 24.hours) {
    Action { implicit request =>
      val staticRoutes = List(
        routes.HomeController.index(),
        routes.HomeController.cookieUsage(),
        routes.HomeController.terms(),
        routes.HomeController.privacy(),
        routes.HomeController.faq(),
        routes.TreeController.ytree(),
        routes.TreeController.mtree(),
        routes.PublicationController.index(),
        routes.CoverageController.index(),
        routes.ContactController.show()
      )

      val baseUrl = s"${
        if (request.secure) {
          "https"
        } else {
          "http"
        }
      }://${request.host}"

        val apiDocsUrl = s"""  <url>
                       |    <loc>$baseUrl/api/docs</loc>
                       |    <changefreq>monthly</changefreq>
                       |    <priority>0.6</priority>
                       |  </url>""".stripMargin

      val xmlContent =
        """<?xml version="1.0" encoding="UTF-8"?>
          |<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
          |""".stripMargin +
          staticRoutes.map { route =>
            s"""  <url>
               |    <loc>${route.absoluteURL(secure = true)}</loc>
               |    <changefreq>weekly</changefreq>
               |    <priority>0.8</priority>
               |  </url>""".stripMargin
          }.mkString("\n") +
          "\n" + apiDocsUrl +
          "\n</urlset>"

      Ok(xmlContent).as("application/xml")
    }
  }

  /**
   * Generates and serves the `robots.txt` file for the application.
   *
   * The robots.txt file provides directives to web crawlers about which parts
   * of the website are accessible for crawling and indexing. It includes a
   * link to the application's sitemap for search engines to discover and index
   * the site's significant URLs efficiently. The response is a plain text file
   * with appropriate directives for crawlers.
   *
   * @return an `EssentialAction` that produces the `robots.txt` file as a plain text response
   *         with a "200 OK" status, including a link to the XML sitemap.
   */
  def robots(): EssentialAction = cached.status(_ => "robots", 200, 24.hours) {
    Action { implicit request =>
      val sitemapUrl = routes.HomeController.sitemap().absoluteURL(secure = true)
      Ok(
        s"""User-agent: *
           |Allow: /
           |
           |Sitemap: $sitemapUrl""".stripMargin
      ).as("text/plain")
    }
  }

  /**
   * Health check endpoint for load balancers and container orchestration.
   *
   * Returns a simple JSON response indicating the application is running.
   * This endpoint is used by Docker health checks, Kubernetes probes,
   * and load balancer health checks.
   *
   * @return an action that returns a 200 OK with a JSON health status
   */
  def health(): Action[AnyContent] = Action {
    Ok("""{"status":"ok"}""").as("application/json")
  }
}
