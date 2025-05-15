package models.api

case class PaginatedResult[T](
                               items: Seq[T],
                               currentPage: Int,
                               pageSize: Int,
                               totalItems: Long
                             ) {
  def totalPages: Int = Math.ceil(totalItems.toDouble / pageSize).toInt
  def hasNextPage: Boolean = currentPage < totalPages
  def hasPreviousPage: Boolean = currentPage > 1
}
