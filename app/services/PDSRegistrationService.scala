package services

import models.PDSRegistration
import play.api.Logging

import javax.inject.{Inject, Singleton}
import repositories.PDSRegistrationRepository
import java.time.ZonedDateTime // Import ZonedDateTime

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class PDSRegistrationService @Inject()(
  atProtocolClient: ATProtocolClient,
  pdsRegistrationRepository: PDSRegistrationRepository
)(implicit ec: ExecutionContext) extends Logging {

  /**
   * Registers a new PDS, performing server-side verification with the AT Protocol.
   *
   * @param did The Decentralized Identifier (DID) of the PDS.
   * @param handle The handle associated with the PDS.
   * @param pdsUrl The base URL of the PDS.
   * @param rToken The AT Protocol authentication token provided by the Researcher Edge App.
   * @return A Future indicating success or failure of the registration.
   */
  def registerPDS(did: String, handle: String, pdsUrl: String, rToken: String): Future[Either[String, PDSRegistration]] = {
    // 1. Check if PDS already registered
    pdsRegistrationRepository.findByDid(did).flatMap {
      case Some(existingRegistration) =>
        Future.successful(Left(s"PDS with DID $did is already registered."))
      case None =>
        // 2. Perform server-side verification with the AT Protocol
        atProtocolClient.getLatestCommit(pdsUrl, did, rToken).flatMap {
          case Some(commitResponse) =>
            // 3. Validation: Confirm DID is valid and PDS is responsive (implicitly done by successful commit fetch)
            // 4. Write New DID Record
            val newRegistration = PDSRegistration(
              did = did,
              pdsUrl = pdsUrl,
              handle = handle,
              lastCommitCid = Some(commitResponse.cid),
              lastCommitSeq = Some(commitResponse.seq),
              cursor = 0L,
              createdAt = ZonedDateTime.now(),
              updatedAt = ZonedDateTime.now()
            )
            pdsRegistrationRepository.create(newRegistration).map { res =>
              logger.info(s"Internal Notification: PDS Registered successfully for DID $did. Rust Sync Cluster will detect this via DB poll.")
              Right(res)
            }
          case None =>
            Future.successful(Left(s"Failed to verify PDS $pdsUrl for DID $did. Could not get latest commit."))
        }
    } recover {
      case e: Exception =>
        logger.error(s"Error during PDS registration for DID $did: ${e.getMessage}", e)
        Left("An unexpected error occurred during PDS registration.")
    }
  }

  /**
   * Retrieves a PDS registration by its DID.
   */
  def getPDSByDid(did: String): Future[Option[PDSRegistration]] = {
    pdsRegistrationRepository.findByDid(did)
  }

  /**
   * Retrieves a PDS registration by its handle.
   */
  def getPDSByHandle(handle: String): Future[Option[PDSRegistration]] = {
    pdsRegistrationRepository.findByHandle(handle)
  }

  /**
   * Lists all registered PDS entries.
   */
  def listAllPDS(): Future[Seq[PDSRegistration]] = {
    pdsRegistrationRepository.listAll
  }

  /**
   * Updates the cursor (last commit CID and sequence) for a registered PDS.
   */
  def updatePDSCursor(did: String, lastCommitCid: String, newCursor: Long): Future[Either[String, Unit]] = {
    pdsRegistrationRepository.updateCursor(did, lastCommitCid, newCursor).map { affectedRows =>
      if (affectedRows > 0) Right(())
      else Left(s"PDS with DID $did not found or cursor update failed.")
    } recover {
      case e: Exception =>
        logger.error(s"Error updating PDS cursor for DID $did: ${e.getMessage}", e)
        Left("An unexpected error occurred during PDS cursor update.")
    }
  }

  /**
   * Deletes a PDS registration.
   */
  def deletePDS(did: String): Future[Either[String, Unit]] = {
    pdsRegistrationRepository.delete(did).map { affectedRows =>
      if (affectedRows > 0) Right(())
      else Left(s"PDS with DID $did not found or deletion failed.")
    } recover {
      case e: Exception =>
        logger.error(s"Error deleting PDS for DID $did: ${e.getMessage}", e)
        Left("An unexpected error occurred during PDS deletion.")
    }
  }
}
