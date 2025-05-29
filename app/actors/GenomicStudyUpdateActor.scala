package actors

import javax.inject.Inject
import models.domain.publications.{PublicationGenomicStudy, StudySource}
import org.apache.pekko.actor.Actor
import org.apache.pekko.stream.{Materializer, ThrottleMode}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import repositories.{BiosampleRepository, GenomicStudyRepository, PublicationBiosampleRepository, PublicationGenomicStudyRepository}
import services.GenomicStudyService

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration.*

object GenomicStudyUpdateActor {
  case class UpdateStudy(accession: String, source: StudySource, publicationId: Option[Int])
  case class UpdateResult(accession: String, success: Boolean, message: String)
}

class GenomicStudyUpdateActor @Inject()(
                                         genomicStudyService: GenomicStudyService,
                                         studyRepository: GenomicStudyRepository,
                                         biosampleRepository: BiosampleRepository,
                                         publicationStudyRepository: PublicationGenomicStudyRepository,
                                         publicationBiosampleRepository: PublicationBiosampleRepository
                                       ) extends Actor {
  import GenomicStudyUpdateActor.*

  implicit val materializer: Materializer = Materializer(context.system)
  implicit val ec: ExecutionContextExecutor = context.dispatcher

  // Rate limiting configuration
  private val elementsPerUnit = 1
  private val perDuration = 2.second  // Increased to handle NCBI rate limits
  private val maxBurst = 1
  private val throttleMode = ThrottleMode.shaping

  def receive = {
    case UpdateStudy(accession, source, publicationId) =>
      val sender = context.sender()

      Source.single(accession)
        .throttle(elementsPerUnit, perDuration, maxBurst, throttleMode)
        .mapAsync(1) { acc =>
          updateStudy(acc, source, publicationId)
        }
        .runWith(Sink.head)
        .foreach(result => sender ! result)
  }

  private def updateStudy(accession: String, source: StudySource, publicationId: Option[Int]) = {
    (for {
      studyOpt <- genomicStudyService.getStudyDetails(accession)
      result <- studyOpt match {
        case Some(study) =>
          for {
            savedStudy <- studyRepository.saveStudy(study)
            biosamples <- genomicStudyService.getBiosamplesForStudy(accession)
            savedBiosamples <- if (biosamples.nonEmpty) {
              biosampleRepository.upsertMany(biosamples)
            } else Future.successful(Seq.empty)
            _ <- publicationId match {
              case Some(pubId) =>
                for {
                  _ <- publicationStudyRepository.create(PublicationGenomicStudy(
                    publicationId = pubId,
                    studyId = savedStudy.id.get
                  ))
                  _ <- Future.sequence(
                    savedBiosamples.flatMap(_.id).map { biosampleId =>
                      publicationBiosampleRepository.create(
                        models.domain.publications.PublicationBiosample(pubId, biosampleId)
                      )
                    }
                  )
                } yield ()
              case None => Future.successful(())
            }
          } yield UpdateResult(
            accession,
            true,
            s"${source} study updated successfully with ${savedBiosamples.size} biosamples" +
              publicationId.map(id => s" and linked to publication $id").getOrElse("")
          )
        case None =>
          Future.successful(UpdateResult(accession, false, s"No data found in ${source}"))
      }
    } yield result).recover {
      case e: Exception =>
        UpdateResult(accession, false, s"Failed to process: ${e.getMessage}")
    }
  }
}