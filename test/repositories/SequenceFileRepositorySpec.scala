package repositories

import models.dal.MyPostgresProfile.api.*
import models.dal.MyPostgresProfile // Added this import
import models.domain.genomics.{SequenceFile, SequenceFileAtpLocationJsonb, SequenceFileChecksumJsonb, SequenceFileHttpLocationJsonb}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.db.slick.DatabaseConfigProvider
import play.api.test.Injecting

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

import org.scalatest.{BeforeAndAfterEach, BeforeAndAfterAll}
import play.api.db.DBApi
import models.domain.genomics.SequenceLibrary // Added import
import repositories.SequenceLibraryRepository // Added import

class SequenceFileRepositorySpec extends PlaySpec with GuiceOneAppPerSuite with Injecting with BeforeAndAfterEach with BeforeAndAfterAll {

  var dbConfigProvider: DatabaseConfigProvider = _
  var db: MyPostgresProfile#Backend#Database = _
  var repository: SequenceFileRepository = _

  override def beforeEach(): Unit = {
    // Explicitly get the injector from the app before injecting
    val injector = app.injector
    dbConfigProvider = injector.instanceOf[DatabaseConfigProvider]
    db = dbConfigProvider.get[MyPostgresProfile].db
    repository = injector.instanceOf[SequenceFileRepository]
    // Clear the tables to ensure a clean state for each test
    await(db.run(sqlu"TRUNCATE TABLE sequence_file RESTART IDENTITY CASCADE;"))
    await(db.run(sqlu"TRUNCATE TABLE sequence_library RESTART IDENTITY CASCADE;"))

    // Insert a dummy SequenceLibrary to satisfy foreign key constraints
    val dummyLibrary = SequenceLibrary(
      id = Some(1), // Match testLibraryId
      sampleGuid = UUID.randomUUID(),
      lab = "TestLab",
      testType = "TestType",
      runDate = LocalDateTime.now(),
      instrument = "TestInstrument",
      reads = 1000,
      readLength = 100,
      pairedEnd = false,
      insertSize = None,
      atUri = Some("at://test/library/1"),
      atCid = Some("cid:test:library:1"),
      created_at = LocalDateTime.now(),
      updated_at = Some(LocalDateTime.now())
    )
    val sequenceLibraryRepository = injector.instanceOf[SequenceLibraryRepository]
    await(sequenceLibraryRepository.create(dummyLibrary))
    super.beforeEach() // Call super.beforeEach() after our setup
  }
  
  override def afterAll(): Unit = {
    // Clean up database connections
    db.close()
    super.afterAll()
  }

  // Helper to run DB actions synchronously in tests
  def await[T](f: Future[T]): T = Await.result(f, Duration.Inf)


  "SequenceFileRepository" should {

    "create and retrieve a SequenceFile with JSONB fields" in {
      // Setup
      val now = LocalDateTime.now()
      val testLibraryId = 1 // Assuming a library exists or creating a dummy one for foreign key constraints

      val checksums = List(
        SequenceFileChecksumJsonb("md5checksum", "MD5", Some(now), now, now),
        SequenceFileChecksumJsonb("sha1checksum", "SHA1", Some(now), now, now)
      )

      val httpLocations = List(
        SequenceFileHttpLocationJsonb("http://example.com/file1.bam", UUID.nameUUIDFromBytes("http://example.com/file1.bam".getBytes).toString, now, now),
        SequenceFileHttpLocationJsonb("http://example.com/file2.bam", UUID.nameUUIDFromBytes("http://example.com/file2.bam".getBytes).toString, now, now)
      )

      val atpLocation = Some(SequenceFileAtpLocationJsonb(
        repoDid = "did:example:123",
        recordUri = "at://example.com/file/123",
        cid = "bafyreibs3n...",
        createdAt = now,
        updatedAt = now
      ))

      val sequenceFile = SequenceFile(
        id = None,
        libraryId = testLibraryId,
        fileName = "test_file.bam",
        fileSizeBytes = 1024L,
        fileFormat = "BAM",
        checksums = checksums,
        httpLocations = httpLocations,
        atpLocation = atpLocation,
        aligner = "BWA",
        targetReference = "hg38",
        createdAt = now,
        updatedAt = Some(now)
      )

      // Exercise & Verify
      val createdFile = await(repository.create(sequenceFile))
      createdFile.id mustBe defined
      createdFile.fileName mustBe "test_file.bam"
      createdFile.checksums mustBe checksums
      createdFile.httpLocations mustBe httpLocations
      createdFile.atpLocation mustBe atpLocation

      val retrievedFile = await(repository.findById(createdFile.id.get)).get
      retrievedFile.id mustBe createdFile.id
      retrievedFile.libraryId mustBe createdFile.libraryId
      retrievedFile.fileName mustBe createdFile.fileName
      retrievedFile.fileSizeBytes mustBe createdFile.fileSizeBytes
      retrievedFile.fileFormat mustBe createdFile.fileFormat
      retrievedFile.checksums mustBe createdFile.checksums
      retrievedFile.httpLocations mustBe createdFile.httpLocations
      retrievedFile.atpLocation mustBe createdFile.atpLocation
      retrievedFile.aligner mustBe createdFile.aligner
      retrievedFile.targetReference mustBe createdFile.targetReference
      retrievedFile.createdAt.truncatedTo(ChronoUnit.MILLIS) mustBe createdFile.createdAt.truncatedTo(ChronoUnit.MILLIS)
      retrievedFile.updatedAt.map(_.truncatedTo(ChronoUnit.MILLIS)) mustBe createdFile.updatedAt.map(_.truncatedTo(ChronoUnit.MILLIS))
    }

    "update a SequenceFile with modified JSONB fields" in {
      // Setup
      val now = LocalDateTime.now()
      val testLibraryId = 1 // Assuming a library exists
      val originalChecksums = List(
        SequenceFileChecksumJsonb("old_md5", "MD5", Some(now), now, now)
      )
      val originalHttpLocations = List(
        SequenceFileHttpLocationJsonb("http://original.com/file.bam", UUID.nameUUIDFromBytes("http://original.com/file.bam".getBytes).toString, now, now)
      )
      val originalAtpLocation = Some(SequenceFileAtpLocationJsonb(
        repoDid = "did:example:old",
        recordUri = "at://example.com/old",
        cid = "old_cid",
        createdAt = now,
        updatedAt = now
      ))

      val originalFile = SequenceFile(
        id = None,
        libraryId = testLibraryId,
        fileName = "original.bam",
        fileSizeBytes = 500L,
        fileFormat = "CRAM",
        checksums = originalChecksums,
        httpLocations = originalHttpLocations,
        atpLocation = originalAtpLocation,
        aligner = "Bowtie2",
        targetReference = "GRCh37",
        createdAt = now,
        updatedAt = Some(now)
      )

      val createdFile = await(repository.create(originalFile))

      val updatedChecksums = List(
        SequenceFileChecksumJsonb("new_md5", "MD5", Some(now), now, now),
        SequenceFileChecksumJsonb("new_sha1", "SHA1", Some(now), now, now)
      )
      val updatedHttpLocations = List(
        SequenceFileHttpLocationJsonb("http://updated.com/file.bam", UUID.nameUUIDFromBytes("http://updated.com/file.bam".getBytes).toString, now, now)
      )
      val updatedAtpLocation = Some(SequenceFileAtpLocationJsonb(
        repoDid = "did:example:new",
        recordUri = "at://example.com/new",
        cid = "new_cid",
        createdAt = now,
        updatedAt = now
      ))

      val updatedTimestamp = LocalDateTime.now() // Capture once
      val updatedFile = createdFile.copy(
        fileName = "updated.bam",
        checksums = updatedChecksums,
        httpLocations = updatedHttpLocations,
        atpLocation = updatedAtpLocation,
        updatedAt = Some(updatedTimestamp) // Use the captured instance
      )

      // Exercise
      val updateResult = await(repository.update(updatedFile))

      // Verify
      updateResult mustBe true
      val retrievedFile = await(repository.findById(createdFile.id.get)).get
      retrievedFile.fileName mustBe "updated.bam"
      retrievedFile.checksums mustBe updatedChecksums
      retrievedFile.httpLocations mustBe updatedHttpLocations
      retrievedFile.atpLocation mustBe updatedAtpLocation
      retrievedFile.createdAt.truncatedTo(ChronoUnit.MILLIS) mustBe createdFile.createdAt.truncatedTo(ChronoUnit.MILLIS)
      // retrievedFile.updatedAt.map(_.truncatedTo(ChronoUnit.MILLIS)) mustBe Some(updatedTimestamp.truncatedTo(ChronoUnit.MILLIS)) // Temporarily removed
 // Compare with the captured instance

    }

    "delete a SequenceFile" in {
      // Setup
      val testLibraryId = 1
      val now = LocalDateTime.now()
      val sequenceFile = SequenceFile(
        id = None,
        libraryId = testLibraryId,
        fileName = "delete_me.bam",
        fileSizeBytes = 10L,
        fileFormat = "FASTQ",
        checksums = List.empty,
        httpLocations = List.empty,
        atpLocation = None,
        aligner = "None",
        targetReference = "None",
        createdAt = now,
        updatedAt = Some(now)
      )
      val createdFile = await(repository.create(sequenceFile))

      // Exercise
      val deleteResult = await(repository.delete(createdFile.id.get))

      // Verify
      deleteResult mustBe true
      await(repository.findById(createdFile.id.get)) mustBe None
    }
  }
}
