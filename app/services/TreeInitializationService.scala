package services

import jakarta.inject.{Inject, Singleton}
import play.api.libs.json.Json
import models.api.TreeDTO
import models.HaplogroupType
import play.api.Logging
import repositories.HaplogroupRevisionRepository

import java.nio.file.{Files, Path, Paths}
import scala.concurrent.{ExecutionContext, Future}

@Singleton  // Note the parentheses for Scala 3
class TreeInitializationService @Inject()(
                                           haplogroupRevisionRepository: HaplogroupRevisionRepository,
                                           treeImporter: TreeImporter,
                                           config: TreeImportConfig
                                         )(implicit ec: ExecutionContext)
 extends Logging {
  private val YDnaTreePath = Paths.get(config.YDnaTreePath)
  private val MtDnaTreePath = Paths.get(config.MtDnaTreePath)

  /**
   * Checks and initializes both Y-DNA and mtDNA trees if needed.
   * @return Future containing a map of tree type to import status
   */
  def initializeIfNeeded(): Future[Map[HaplogroupType, Boolean]] = {
    for {
      // Check each tree type independently
      yTreeStatus <- initializeTreeType(HaplogroupType.Y, YDnaTreePath)
      mtTreeStatus <- initializeTreeType(HaplogroupType.MT, MtDnaTreePath)
    } yield Map(
      HaplogroupType.Y -> yTreeStatus,
      HaplogroupType.MT -> mtTreeStatus
    )
  }

  private def initializeTreeType(
                                  haplogroupType: HaplogroupType,
                                  filePath: Path
                                ): Future[Boolean] = {
    for {
      // Check if this tree type exists in DB
      isEmpty <- isTreeTypeEmpty(haplogroupType)
      // Check if import file exists
      fileExists = Files.exists(filePath)
      // Perform import if conditions are met
      result <- (isEmpty, fileExists) match {
        case (true, true) =>
          logger.info(s"Importing ${haplogroupType} tree from ${filePath}")
          importFromFile(filePath, haplogroupType)
        case (false, _) =>
          logger.info(s"${haplogroupType} tree already exists in database, skipping import")
          Future.successful(false)
        case (_, false) =>
          logger.warn(s"Import file not found for ${haplogroupType} tree at ${filePath}")
          Future.successful(false)
      }
    } yield result
  }

  private def isTreeTypeEmpty(haplogroupType: HaplogroupType): Future[Boolean] = {
    haplogroupRevisionRepository.countByType(haplogroupType).map(_ == 0)
  }

  private def importFromFile(path: Path, haplogroupType: HaplogroupType): Future[Boolean] = {
    Future {
      val content = Files.readString(path)
      Json.parse(content).as[TreeDTO]
    }.flatMap { tree =>
      treeImporter.importTree(tree, haplogroupType)
        .map { _ =>
          logger.info(s"Successfully imported ${haplogroupType} tree")
          true
        }
    }.recover { case ex =>
      logger.error(s"Failed to import ${haplogroupType} tree", ex)
      false
    }
  }
}
