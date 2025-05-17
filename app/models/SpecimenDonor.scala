package models

/**
 * Represents a specimen donor with metadata about their unique identifier, associated biobank, 
 * and other essential details. This class is typically used to link donors to biosamples and 
 * other related entities in a biobank or genomic research context. 
 *
 * @param id              An optional unique identifier for the specimen donor, used internally for tracking purposes.
 * @param donorIdentifier A required unique identifier for the donor, providing a way to reference the donor 
 *                        across different datasets or within a biobank.
 * @param originBiobank   The name or identifier of the biobank where the specimen donor originates.
 */
case class SpecimenDonor(id: Option[Int] = None, donorIdentifier: String, originBiobank: String)
