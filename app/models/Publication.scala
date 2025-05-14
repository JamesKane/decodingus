package models

import java.time.LocalDate

case class Publication(
                        id: Option[Int] = None,
                        pubmedId: String,
                        doi: String,
                        title: String,
                        journal: String,
                        publicationDate: LocalDate,
                        url: String
                      )
