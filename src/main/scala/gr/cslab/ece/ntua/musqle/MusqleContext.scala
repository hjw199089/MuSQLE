package gr.cslab.ece.ntua.musqle

import gr.cslab.ece.ntua.musqle.catalog.Catalog
import gr.cslab.ece.ntua.musqle.spark.DPhypSpark
import org.apache.spark.sql.{DataFrame, Dataset, Row, SparkSession}
import gr.cslab.ece.ntua.musqle.benchmarks.tpcds.AllQueries
import gr.cslab.ece.ntua.musqle.engine.Engine
import gr.cslab.ece.ntua.musqle.sql.SparkPlanGenerator
import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.log4j.Logger
import org.apache.log4j.Level

class MusqleContext {
  Logger.getLogger("org").setLevel(Level.OFF)
  Logger.getLogger("akka").setLevel(Level.OFF)

  lazy val sparkSession = SparkSession
    .builder()
    .master("spark://localhost:7077").appName("MuSQLE")
    .config("spark.files", "./jars/postgresql-9.4.1212.jre6.jar")
    .config("spark.jars", "./jars/postgresql-9.4.1212.jre6.jar")
    .getOrCreate()

  val catalog = Catalog.getInstance
  val planner: DPhypSpark = new DPhypSpark(sparkSession)

  val post = Engine.POSTGRES(sparkSession)

  catalog.tableMap.values.foreach { tableEntry =>
  val tableDF = {
    tableEntry.engine match {
      case "spark" => {
        sparkSession.read.format (tableEntry.format).load(tableEntry.tablePath)
      }
      case "postgres" => {
        sparkSession.read.jdbc(post.jdbcURL, tableEntry.tablePath, post.props)
      }
    }
  }
    planner.qInfo.planToTableName.put(tableDF.queryExecution.optimizedPlan.asInstanceOf[LogicalRelation], tableEntry.tableName)
    tableDF.createOrReplaceTempView(tableEntry.tableName)
  }


  def query(sql: String): DataFrame = {
    val df = sparkSession.sql(sql)
    val optPlan = df.queryExecution.optimizedPlan
    planner.setLogicalPlan(optPlan)

    val p = planner.plan()
    val planGenerator = new SparkPlanGenerator(sparkSession)
    val sparkLogical = planGenerator.toSparkLogicalPlan(p)

    //val qe = new QueryExecution(sparkSession, sparkLogical)
    val dataFrame = new Dataset[Row](sparkSession, sparkLogical, RowEncoder(sparkLogical.schema))

    p.explain()
    println(sparkLogical)

    /*
    var sp = 0.0
    var musqle = 0.0

    val a = {
      val start = System.currentTimeMillis()
      musqle = df.count()
      System.currentTimeMillis() - start
    }
    val b = {
      val start = System.currentTimeMillis()
      sp = dataFrame.count()
      System.currentTimeMillis() - start
    }
    println(s"Spark: [$sp][${a / 1000.0}]\nMuSQLE: [$musqle][${b / 1000.0}]")
    */

    dataFrame
  }

}

object test extends App{
  val mc = new MusqleContext()
  val q = AllQueries.tpcds1_4Queries(11)._2
  val t = """select * from date_dim d1, date_dim d2, date_dim d3, date_dim d4
            |where d1.d_date_sk = d2.d_date_sk
            |and d2.d_date_sk = d3.d_date_sk
            |and d3.d_date_sk = d4.d_date_sk""".stripMargin

  mc.query(q)
}