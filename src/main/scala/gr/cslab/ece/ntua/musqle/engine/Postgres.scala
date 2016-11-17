package gr.cslab.ece.ntua.musqle.engine

import java.util.Properties

import com.github.mauricio.async.db.{QueryResult, RowData}
import gr.cslab.ece.ntua.musqle.plan.hypergraph.{DPJoinPlan, Scan}
import org.apache.spark.sql.{DataFrame, SparkSession}
import com.github.mauricio.async.db.postgresql.PostgreSQLConnection
import com.github.mauricio.async.db.postgresql.util.URLParser
import gr.cslab.ece.ntua.musqle.plan.spark.MuSQLEScan

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}

/**
  * Created by vic on 7/11/2016.
  */
case class Postgres(sparkSession: SparkSession) extends Engine {
  val jdbcURL = s"jdbc:postgresql://147.102.4.129:5432/tpcds1?user=musqle&password=musqle"
  val props = new Properties()
  logger.info("Initializing postgres")
  props.setProperty("driver", "org.postgresql.Driver")

  val connection = {
    val configuration = URLParser.parse(jdbcURL)
    val con = new PostgreSQLConnection(configuration)
    Await.result(con.connect, 5 seconds)

    con
  }

  override def createView(plan: MuSQLEScan, srcTable: String, projection: String): Unit = {
    val viewQuery =
      s"""
         |CREATE OR REPLACE VIEW ${plan.tmpName}
         |AS SELECT $projection
         |FROM $srcTable
         |""".stripMargin

    Await.result(connection.sendQuery(viewQuery), 20 seconds)
  }

  override def inject(plan: DPJoinPlan): Unit = {

  }
  override def supportsMove(engine: Engine): Boolean = false
  override def move(dPJoinPlan: DPJoinPlan): Unit = {}
  override def getMoveCost(plan: DPJoinPlan): Double = 100000
  override def getQueryCost(sql: String): Double = {
    logger.debug(s"Getting query cost: ${sql}")

    val future: Future[QueryResult] = connection.sendQuery(s"EXPLAIN ${sql.replaceAll("`", "")}")
    val mapResult: Future[Any] = future.map(queryResult => queryResult.rows match {
      case Some(resultSet) => {
        val row: RowData = resultSet.head
        row(0)
      }
      case None => -1
    })

    lazy val pageFetches = {
      Await.result(mapResult, 20 seconds)
      val p = mapResult.value.get.get.toString
        .split("  ")(1)
        .split(" ")(0)
        .split("\\.\\.")

      val min = p(0).split("=")(1)
      val max = p(1).toDouble

      max
    }

    val singleFetchCost = 1
    val cost = pageFetches * singleFetchCost

    cost
  }

  def writeDF(dataFrame: DataFrame, name: String): Unit = {
    dataFrame.write.jdbc(jdbcURL, name, props)
  }


  def getDF(sql: String): DataFrame = {
    val df = sparkSession.read.jdbc(jdbcURL, s"""(${sql}) AS SubQuery""", props)
    df
  }

  override def toString: String = "PostgreSQL"
}
