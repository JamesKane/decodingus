package services

import jakarta.inject.{Inject, Singleton}
import models.domain.pds.{PdsSubmission, SubmissionSummary}
import play.api.Logging
import play.api.libs.json.JsValue
import repositories.{PdsNodeRepository, PdsSubmissionRepository}

import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SubmissionProvenanceService @Inject()(
                                             submissionRepo: PdsSubmissionRepository,
                                             nodeRepo: PdsNodeRepository
                                           )(implicit ec: ExecutionContext) extends Logging {

  def recordSubmission(
                        did: String,
                        submissionType: String,
                        proposedValue: String,
                        biosampleId: Option[Int] = None,
                        biosampleGuid: Option[UUID] = None,
                        confidenceScore: Option[Double] = None,
                        algorithmVersion: Option[String] = None,
                        softwareVersion: Option[String] = None,
                        payload: Option[JsValue] = None,
                        atUri: Option[String] = None,
                        atCid: Option[String] = None
                      ): Future[Either[String, PdsSubmission]] = {
    if (!PdsSubmission.ValidTypes.contains(submissionType))
      return Future.successful(Left(s"Invalid submission type: $submissionType"))

    nodeRepo.findByDid(did).flatMap {
      case None => Future.successful(Left(s"PDS node not registered: $did"))
      case Some(node) =>
        val submission = PdsSubmission(
          pdsNodeId = node.id.get,
          submissionType = submissionType,
          biosampleId = biosampleId,
          biosampleGuid = biosampleGuid,
          proposedValue = proposedValue,
          confidenceScore = confidenceScore,
          algorithmVersion = algorithmVersion,
          softwareVersion = softwareVersion.orElse(node.softwareVersion),
          payload = payload,
          atUri = atUri,
          atCid = atCid
        )
        submissionRepo.create(submission).map(Right(_))
    }
  }

  def acceptSubmission(submissionId: Int, reviewedBy: String, notes: Option[String] = None): Future[Either[String, Boolean]] = {
    updateSubmissionStatus(submissionId, "ACCEPTED", reviewedBy, notes)
  }

  def rejectSubmission(submissionId: Int, reviewedBy: String, notes: Option[String] = None): Future[Either[String, Boolean]] = {
    updateSubmissionStatus(submissionId, "REJECTED", reviewedBy, notes)
  }

  def supersedeSubmission(submissionId: Int, reviewedBy: String, notes: Option[String] = None): Future[Either[String, Boolean]] = {
    updateSubmissionStatus(submissionId, "SUPERSEDED", reviewedBy, notes)
  }

  def getSubmission(id: Int): Future[Option[PdsSubmission]] =
    submissionRepo.findById(id)

  def getSubmissionsForNode(did: String, submissionType: Option[String] = None): Future[Either[String, Seq[PdsSubmission]]] = {
    nodeRepo.findByDid(did).flatMap {
      case None => Future.successful(Left("PDS node not found"))
      case Some(node) =>
        val result = submissionType match {
          case Some(t) => submissionRepo.findByNodeAndType(node.id.get, t)
          case None => submissionRepo.findByNode(node.id.get)
        }
        result.map(Right(_))
    }
  }

  def getSubmissionsForBiosample(biosampleId: Int): Future[Seq[PdsSubmission]] =
    submissionRepo.findByBiosampleId(biosampleId)

  def getSubmissionsForBiosampleGuid(guid: UUID): Future[Seq[PdsSubmission]] =
    submissionRepo.findByBiosampleGuid(guid)

  def getPendingSubmissions(submissionType: Option[String] = None, limit: Int = 100): Future[Seq[PdsSubmission]] =
    submissionType match {
      case Some(t) => submissionRepo.findByTypeAndStatus(t, "PENDING", limit)
      case None => submissionRepo.findByStatus("PENDING", limit)
    }

  def getNodeSubmissionSummary(did: String): Future[Either[String, SubmissionSummary]] = {
    nodeRepo.findByDid(did).flatMap {
      case None => Future.successful(Left("PDS node not found"))
      case Some(node) =>
        submissionRepo.countByNodeAndStatus(node.id.get).map { counts =>
          val total = counts.values.sum
          val accepted = counts.getOrElse("ACCEPTED", 0)
          val rejected = counts.getOrElse("REJECTED", 0)
          val reviewed = accepted + rejected
          val rate = if (reviewed > 0) accepted.toDouble / reviewed else 0.0

          Right(SubmissionSummary(
            pdsNodeId = node.id.get,
            did = did,
            totalSubmissions = total,
            pendingCount = counts.getOrElse("PENDING", 0),
            acceptedCount = accepted,
            rejectedCount = rejected,
            acceptanceRate = rate
          ))
        }
    }
  }

  private def updateSubmissionStatus(
                                      submissionId: Int,
                                      newStatus: String,
                                      reviewedBy: String,
                                      notes: Option[String]
                                    ): Future[Either[String, Boolean]] = {
    submissionRepo.findById(submissionId).flatMap {
      case None => Future.successful(Left("Submission not found"))
      case Some(submission) if submission.status != "PENDING" =>
        Future.successful(Left(s"Cannot update submission with status: ${submission.status}"))
      case Some(_) =>
        submissionRepo.updateStatus(submissionId, newStatus, Some(reviewedBy), notes).map { success =>
          if (success) Right(true)
          else Left("Failed to update submission status")
        }
    }
  }
}
