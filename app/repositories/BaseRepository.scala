package repositories

import jakarta.inject.Inject
import models.dal.{DatabaseSchema, MyPostgresProfile}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.GetResult

import scala.concurrent.{ExecutionContext, Future}

/**
 * The BaseRepository class provides an abstract layer for accessing the database
 * using the MyPostgresProfile and Slick. It includes various helper methods for
 * common database operations, such as query execution, pagination, raw SQL execution,
 * and more. It serves as a base class for other repository implementations.
 *
 * Constructor Parameters:
 *
 * @param dbConfigProvider Injected DatabaseConfigProvider that provides the database
 *                         configuration for Slick.
 * @param ec               Implicit ExecutionContext for handling asynchronous database operations.
 *
 *                         Features:
 *                         - Provides access to Slick's database configuration and API.
 *                         - Supports transactional and non-transactional queries.
 *                         - Includes pagination utilities for query results.
 *                         - Enables safe execution of raw SQL queries with type mapping.
 *                         - Offers helper methods for executing Common Table Expression (CTE) queries.
 *                         - Allows counting rows with optional filtering and distinct column selection.
 *                         - Supports additional custom functions such as fetching paginated raw SQL results.
 *
 *                         The class can be extended to define repositories specific to individual models 
 *                         or business entities, facilitating DRY principles by reusing the provided abstraction.
 */
abstract class BaseRepository @Inject()(
                                         protected val dbConfigProvider: DatabaseConfigProvider
                                       )(implicit protected val ec: ExecutionContext)
  extends HasDatabaseConfigProvider[MyPostgresProfile] {

  // Required for Slick operators
  protected val api = models.dal.MyPostgresProfile.api

  import api.* // This brings === and other operators into scope


  // Common schema access
  protected val schema = DatabaseSchema

  // Common query helpers
  protected def runQuery[T](query: DBIO[T]): Future[T] = db.run(query)

  protected def runTransactionally[T](actions: DBIO[T]): Future[T] = {
    db.run(actions.transactionally)
  }

  // Common pagination helper
  protected def paginate[E, U](query: Query[E, U, Seq], page: Int, pageSize: Int): Query[E, U, Seq] = {
    query.drop((page - 1) * pageSize).take(pageSize)
  }

  // Common raw SQL helper with type safety
  protected def rawSQL[T](sql: String)(implicit rconv: GetResult[T]): Future[Seq[T]] = {
    runQuery(sql"#$sql".as[T])
  }

  // Common single result helper
  protected def single[T](query: Query[_, T, Seq]): Future[Option[T]] = {
    runQuery(query.result.headOption)
  }

  // New helper methods
  protected def paginatedQuery[T](
                                   baseSQL: String,
                                   page: Int,
                                   pageSize: Int,
                                   params: (String, Any)*
                                 )(implicit rconv: GetResult[T]): Future[Seq[T]] = {
    val offset = (page - 1) * pageSize
    val paginatedSQL =
      s"""
      ${baseSQL}
      LIMIT $pageSize OFFSET $offset
    """
    rawSQL(paginatedSQL)
  }

  protected def countQuery(
                            tableName: String,
                            whereClause: String = "",
                            distinctColumn: String = "*"
                          ): Future[Long] = {
    val sql =
      s"""
      SELECT COUNT(DISTINCT $distinctColumn)
      FROM $tableName
      ${if (whereClause.nonEmpty) s"WHERE $whereClause" else ""}
    """
    rawSQL[Long](sql).map(_.head)
  }

  // Helper for WITH queries
  protected def withCTE[T](
                            cteDefinition: String,
                            mainQuery: String
                          )(implicit rconv: GetResult[T]): Future[Seq[T]] = {
    val sql =
      s"""
      WITH $cteDefinition
      $mainQuery
    """
    rawSQL(sql)
  }
}
