package models

import java.util.UUID

case class BiosampleHaplogroup(sampleGuid: UUID, yHaplogroupId: Option[Int], mtHaplogroupId: Option[Int])

