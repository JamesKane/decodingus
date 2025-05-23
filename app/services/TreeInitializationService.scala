package services

import jakarta.inject.{Inject, Singleton}
import models.HaplogroupType
import models.api.TreeDTO
import play.api.Logging
import play.api.libs.json.Json
import repositories.HaplogroupRevisionRepository

import java.nio.file.{Files, Path, Paths}
import scala.concurrent.{ExecutionContext, Future}

/**
 * Service responsible for initializing haplogroup trees (e.g., Y-DNA and mtDNA trees).
 * This service checks and imports missing tree data from specified files, if needed.
 *
 * @constructor Creates an instance of TreeInitializationService with injected dependencies.
 * @param haplogroupRevisionRepository Repository for accessing haplogroup revision data.
 * @param treeImporter                 Component responsible for importing tree structures into the database.
 * @param config                       Configuration object containing file paths and related settings for tree imports.
 * @param ec                           ExecutionContext for handling asynchronous operations.
 */
@Singleton
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
   *
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
