package models

import java.time.LocalDate

case class Publication(
                        id: Option[Int] = None,
                        pubmedId: Option[String],
                        doi: Option[String],
                        title: String,
                        journal: Option[String],
                        publicationDate: Option[LocalDate],
                        url: Option[String]
                      )
