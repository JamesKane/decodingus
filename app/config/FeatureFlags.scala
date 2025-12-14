package config

import jakarta.inject.{Inject, Singleton}
import play.api.Configuration

/**
 * Configuration wrapper for feature flags.
 * Allows features to be enabled/disabled via application.conf.
 */
@Singleton
class FeatureFlags @Inject()(config: Configuration) {

  private val featuresConfig = config.getOptional[Configuration]("features").getOrElse(Configuration.empty)

  /**
   * Show branch age estimates (Formed/TMRCA dates) on tree nodes.
   * Disabled by default until age data is populated.
   */
  val showBranchAgeEstimates: Boolean = featuresConfig.getOptional[Boolean]("tree.showBranchAgeEstimates").getOrElse(false)

  /**
   * Show the alternative "Block Layout" (ytree.net style) for the tree.
   */
  val showVerticalTree: Boolean = featuresConfig.getOptional[Boolean]("tree.showVerticalTree").getOrElse(false)
}
