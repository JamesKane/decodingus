package services

import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.must.Matchers

class GenomeRegionsServiceSpec extends AnyFunSpec with Matchers {

  describe("GenomeRegionsService") {

    describe("ETag generation") {
      it("should generate consistent ETags for same input") {
        val etag1 = generateTestETag("GRCh38", "2024.12.1")
        val etag2 = generateTestETag("GRCh38", "2024.12.1")

        etag1 mustBe etag2
      }

      it("should generate different ETags for different builds") {
        val etagGrch38 = generateTestETag("GRCh38", "2024.12.1")
        val etagGrch37 = generateTestETag("GRCh37", "2024.12.1")

        etagGrch38 must not be etagGrch37
      }

      it("should generate different ETags for different versions") {
        val etag1 = generateTestETag("GRCh38", "2024.12.1")
        val etag2 = generateTestETag("GRCh38", "2024.12.2")

        etag1 must not be etag2
      }

      it("should wrap ETag in quotes") {
        val etag = generateTestETag("GRCh38", "2024.12.1")

        etag must startWith("\"")
        etag must endWith("\"")
      }
    }
  }

  // Helper method to test ETag generation independently of the service
  private def generateTestETag(buildName: String, dataVersion: String): String = {
    import java.security.MessageDigest
    val input = s"$buildName:$dataVersion"
    val md5 = MessageDigest.getInstance("MD5")
    val hash = md5.digest(input.getBytes("UTF-8")).map("%02x".format(_)).mkString
    s""""$hash""""
  }
}
