package com.kenkoooo.atcoder.db

import com.kenkoooo.atcoder.common.SubmissionStatus
import com.kenkoooo.atcoder.common.TypeAnnotations.{ContestId, ProblemId, UserId}
import com.kenkoooo.atcoder.db.SqlClient._
import com.kenkoooo.atcoder.model._
import org.apache.logging.log4j.scala.Logging
import scalikejdbc._
import sqls.{count, distinct}
import SQLSyntax.min
import scalikejdbc.interpolation.SQLSyntax

import scala.util.Try

/**
  * Data Store of SQL
  *
  * @param url      JDBC url of SQL
  * @param user     username of SQL
  * @param password password of SQL
  * @param driver   driver name to connect to SQL
  */
class SqlClient(url: String,
                user: String,
                password: String,
                driver: String = "com.mysql.cj.jdbc.Driver")
    extends Logging {
  Class.forName(driver)
  ConnectionPool.singleton(url, user, password)

  private var _contests: Map[ContestId, Contest] = Map()
  private var _problems: Map[ProblemId, Problem] = Map()
  private var _lastReloaded: Long = 0

  def contests: Map[String, Contest] = _contests

  def problems: Map[String, Problem] = _problems

  def lastReloadedTimeMillis: Long = _lastReloaded

  private[db] def executeAndLoadSubmission(builder: SQLBuilder[_]): List[Submission] = {
    DB.readOnly { implicit session =>
      withSQL(builder).map(Submission(submissionSyntax)).list().apply()
    }
  }

  /**
    * Load submissions with given ids from SQL
    *
    * @param ids ids of submissions to load
    * @return list of loaded submissions
    */
  def loadSubmissions(ids: Long*): Iterator[Submission] = {
    SubmissionIterator(
      this,
      selectFrom(Submission as submissionSyntax).where.in(submissionSyntax.id, ids)
    )
  }

  /**
    * load submissions which are submitted by anyone in the given list
    *
    * @param userIds [[UserId]] to search submissions
    * @return [[Iterator]] of [[Submission]]
    */
  def loadUserSubmissions(userIds: UserId*): Iterator[Submission] = {
    SubmissionIterator(
      this,
      selectFrom(Submission as submissionSyntax).where.in(submissionSyntax.userId, userIds)
    )
  }

  /**
    * load all the accepted submissions
    *
    * @return all the accepted submissions
    */
  def loadAllAcceptedSubmissions(): Iterator[Submission] = {
    SubmissionIterator(
      this,
      selectFrom(Submission as submissionSyntax).where
        .eq(submissionSyntax.c("result"), SubmissionStatus.Accepted)
    )
  }

  def updateSolverCounts(): Unit = {
    val v = Solver.column
    val s = Submission.syntax("s")
    DB.localTx { implicit session =>
      withSQL { deleteFrom(Solver) }.execute().apply()
      withSQL {
        insertInto(Solver)
          .columns(v.solvers, v.problemId)
          .select(sqls"${count(distinct(s.userId))}", s.problemId)(
            _.from(Submission as s).where
              .eq(s.c("result"), SubmissionStatus.Accepted)
              .groupBy(s.problemId)
          )
      }.execute().apply()
    }
  }

  def extractGreatSubmissions(): Unit = {
    val shortest = Shortest.column
    val submission = Submission.syntax("s")
    val blank = sqls"' '"
    DB.localTx { implicit session =>
      withSQL { deleteFrom(Shortest) }.execute().apply()
      withSQL {
        insertInto(Shortest)
          .columns(shortest.problemId, shortest.submissionId)
          .select(submission.problemId, min(submission.id))(
            _.from(Submission as submission).where
              .in(
                concat(submission.problemId, blank, submission.length),
                select(concat(submission.problemId, blank, min(submission.length)))
                  .from(Submission as submission)
                  .where
                  .eq(submission.c("result"), SubmissionStatus.Accepted)
                  .groupBy(submission.problemId)
              )
              .and
              .eq(submission.c("result"), SubmissionStatus.Accepted)
              .groupBy(submission.problemId)
          )
      }.update().apply()
    }
  }

  /**
    * reload contests and problems
    */
  def reloadRecords(): Unit = {
    _contests = reload(Contest).map(s => s.id -> s).toMap
    _problems = reload(Problem).map(s => s.id -> s).toMap
    _lastReloaded = System.currentTimeMillis()
  }

  def reload[T](support: SQLInsertSelectSupport[T]): Seq[T] = {
    logger.info(s"reloading ${support.tableName}")
    DB.readOnly { implicit session =>
      val s = support.syntax("s")
      withSQL(select.from(support as s))
        .map(support(s))
        .list()
        .apply()
    }
  }

  /**
    * insert records to SQL
    *
    * @param support support object of inserting records
    * @param records seq of records to insert
    * @tparam T type of records
    */
  def batchInsert[T](support: SQLInsertSelectSupport[T], records: T*): Unit = this.synchronized {
    Try {
      DB.localTx { implicit session =>
        val params = support.createMapping(records).map(seq => seq.map(_._2)).map(seq => seq ++ seq)
        val columnMapping = support.createMapping(records).head.map(_._1 -> sqls.?)
        withSQL {
          insertInto(support)
            .namedValues(columnMapping: _*)
            .onDuplicateKeyUpdate(columnMapping: _*)
        }.batch(params: _*).apply()
      }
    }.recover {
      case e: Throwable =>
        logger.catching(e)
        records.foreach(t => logger.error(t.toString))
    }
  }
}

private object SqlClient {
  private val submissionSyntax = Submission.syntax("s")

  implicit class RichInsertSQLBuilder(val self: InsertSQLBuilder) extends AnyVal {
    def onDuplicateKeyUpdate(columnsAndValues: (SQLSyntax, Any)*): InsertSQLBuilder = {
      val cvs = columnsAndValues map {
        case (c, v) => sqls"$c = $v"
      }
      self.append(sqls"on duplicate key update ${sqls.csv(cvs: _*)}")
    }
  }

  implicit class RichSQLSyntax(val self: sqls.type) extends AnyVal {
    def values(column: SQLSyntax): SQLSyntax = sqls"values($column)"
  }

  def concat(columns: SQLSyntax*): SQLSyntax = sqls"concat(${sqls.csv(columns: _*)})"
}

/**
  * [[Iterator]] of [[Submission]] to iterate all the submission without expanding all the result to memory
  *
  * @param sqlClient [[SqlClient]] to connect to SQL
  * @param builder   [[SQLBuilder]] of selecting query
  * @param fetchSize the number of records in each fetch
  */
case class SubmissionIterator(sqlClient: SqlClient,
                              builder: SQLBuilder[_],
                              fetchSize: Int = SubmissionIterator.DefaultLimit)
    extends Iterator[Submission] {
  private var offset = 0
  private var currentList = List[Submission]()

  override def hasNext: Boolean = this.synchronized {
    if (currentList.isEmpty) {
      reload()
    }
    currentList.nonEmpty
  }

  override def next(): Submission = this.synchronized {
    require(hasNext)
    val head = currentList.head
    currentList = currentList.tail
    head
  }

  private def reload(): Unit = this.synchronized {
    currentList = sqlClient.executeAndLoadSubmission {
      builder.append(sqls.limit(fetchSize)).append(sqls.offset(offset))
    }
    offset += currentList.size
  }
}

object SubmissionIterator {
  private val DefaultLimit = 100000
}