package models

import java.util.UUID

case class PgpBiosample(
                         id: Option[Int],
                         pgpParticipantId: String,
                         // ... other PGP metadata
                         sampleGuid: UUID
                       )
