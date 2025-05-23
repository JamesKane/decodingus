package services

import javax.inject.{Inject, Singleton}
import play.api.Configuration

@Singleton
class TreeImportConfig @Inject()(configuration: Configuration) {
  val YDnaTreePath: String = "/tmp/import-tree-ydna.json"
  val MtDnaTreePath: String = "/tmp/import-tree-mtdna.json"
}