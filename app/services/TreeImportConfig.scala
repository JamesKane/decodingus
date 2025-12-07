package services

import play.api.Configuration

import javax.inject.{Inject, Singleton}

@Singleton
class TreeImportConfig @Inject()(configuration: Configuration) {
  val YDnaTreePath: String = "/tmp/import-tree-ydna.json"
  val MtDnaTreePath: String = "/tmp/import-tree-mtdna.json"
}