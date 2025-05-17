package models

import java.util.UUID

/**
 * Represents the association between a biological sample and its haplogroups.
 *
 * @param sampleGuid     The universally unique identifier (UUID) for the biological sample.
 * @param yHaplogroupId  An optional identifier for the Y-haplogroup associated with the sample.
 * @param mtHaplogroupId An optional identifier for the mitochondrial (MT) haplogroup associated with the sample.
 */
case class BiosampleHaplogroup(sampleGuid: UUID, yHaplogroupId: Option[Int], mtHaplogroupId: Option[Int])

