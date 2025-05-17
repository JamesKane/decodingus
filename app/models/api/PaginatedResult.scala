package models.api

/**
 * Represents a paginated result set for a collection of items.
 *
 * @tparam T The type of items contained in the result set.
 * @param items       The items for the current page.
 * @param currentPage The current page number (1-based index).
 * @param pageSize    The maximum number of items per page.
 * @param totalItems  The total number of items across all pages.
 */
case class PaginatedResult[T](
                               items: Seq[T],
                               currentPage: Int,
                               pageSize: Int,
                               totalItems: Long
                             ) {
  /**
   * Computes the total number of pages based on the total number of items and the page size.
   *
   * @return The total number of pages as an integer, calculated by dividing the total number of items
   *         by the page size and rounding up to the nearest whole number.
   */
  def totalPages: Int = Math.ceil(totalItems.toDouble / pageSize).toInt

  /**
   * Determines whether there is a next page available in the paginated result set.
   *
   * @return True if the current page number is less than the total number of pages, indicating that there is a next page; otherwise, false.
   */
  def hasNextPage: Boolean = currentPage < totalPages

  /**
   * Determines whether there is a previous page available in the paginated result set.
   *
   * @return True if the current page number is greater than 1, indicating that there is a previous page; otherwise, false.
   */
  def hasPreviousPage: Boolean = currentPage > 1
}
