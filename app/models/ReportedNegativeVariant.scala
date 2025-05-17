package models

import java.time.LocalDateTime
import java.util.UUID

/**
 * Represents a reported negative variant associated with a specific sample and variant.
 *
 * @param id           An optional unique identifier for the reported negative variant, primarily used for internal purposes.
 * @param sampleGuid   The universally unique identifier (UUID) of the sample associated with this reported variant.
 * @param variantId    The identifier of the variant being reported as negative.
 * @param reportedDate The date and time when this negative variant report was created.
 * @param notes        Optional additional notes or comments about the reported negative variant.
 * @param status       The status of the reported variant, typically indicating its current state or resolution.
 */
case class ReportedNegativeVariant(
                                    id: Option[Long],
                                    sampleGuid: UUID,
                                    variantId: Int,
                                    reportedDate: LocalDateTime,
                                    notes: Option[String],
                                    status: String,
                                  )
