package models.domain

/**
 * Represents an analysis method used in ancestry or genetic analysis.
 *
 * @param id         The unique identifier for the analysis method. This is optional.
 * @param methodName The name of the analysis method, providing details about the approach or technique used.
 */
case class AnalysisMethod(id: Option[Int], methodName: String)
