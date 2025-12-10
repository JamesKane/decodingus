package config

import jakarta.inject.{Inject, Singleton}
import play.api.Configuration

import java.io.File

/**
 * Configuration wrapper for genomics-related settings.
 */
@Singleton
class GenomicsConfig @Inject()(config: Configuration) {

  private val genomicsConfig = config.get[Configuration]("genomics")

  val supportedReferences: Seq[String] = genomicsConfig.get[Seq[String]]("references.supported")

  val referenceAliases: Map[String, String] = genomicsConfig.getOptional[Map[String, String]]("references.aliases").getOrElse(Map.empty)

  val fastaPaths: Map[String, File] = genomicsConfig.get[Map[String, String]]("references.fasta_paths").map {
    case (genome, path) => genome -> new File(path)
  }

  // YBrowse configuration
  val ybrowseVcfUrl: String = genomicsConfig.get[String]("ybrowse.vcf_url")
  val ybrowseVcfStoragePath: File = new File(genomicsConfig.get[String]("ybrowse.vcf_storage_path"))

  /**
   * Retrieves the path to a liftover chain file for a given source and target genome.
   *
   * @param source The source reference genome (e.g., "GRCh38").
   * @param target The target reference genome (e.g., "GRCh37").
   * @return An Option containing the File if the chain is configured and exists, otherwise None.
   */
  def getLiftoverChainFile(source: String, target: String): Option[File] = {
    val key = s"$source->$target"
    genomicsConfig.getOptional[String](s"liftover.chains.\"$key\"").map(new File(_))
  }

  /**
   * Resolves a genome name to its canonical form using the aliases configuration.
   *
   * @param name The input genome name.
   * @return The canonical name if an alias exists, otherwise the input name.
   */
  def resolveReferenceName(name: String): String = {
    referenceAliases.getOrElse(name, name)
  }
}
