package sensors

import akka.actor.ActorSystem
import akka.stream.IOResult
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}

object SensorDataProcess {
  case class Stats(numFiles: Int, numOfRecords: Long, numOfFailedRecords: Long,
                   deviceWithSortedMinAvgMax: Array[String], deviceReportedWithOnlyNans: Array[String])

  Logger.getLogger("org.apache.spark").setLevel(Level.OFF);

  def getLinesFromCSVFiles(dirPath: String): (Int, Future[Array[String]]) = {
    import java.io.File
    implicit val system = ActorSystem("Sys")
    val directory = new File(dirPath)
    val csvFiles = directory.listFiles.filter(_.toString.contains(".csv"))
    val numOfFiles = csvFiles.length
    val lines = Source(csvFiles)
      .mapAsyncUnordered(8) { case csvFile =>
        val fileSource = FileIO.fromFile(csvFile, 8192)
        val chunkStrs: Source[String, Future[IOResult]] = fileSource.map((chunk: ByteString) => chunk.utf8String)
        val strs: Future[String] = chunkStrs.runFold("") { (fsmResponses, nextResponse) => (fsmResponses + "\n").concat(nextResponse) }
        strs
      }
    val allFileLines = lines.runFold("") { (fsmResponses, nextResponse) => fsmResponses.concat(nextResponse) }.map(_.split("\n"))

    (numOfFiles, allFileLines)
  }

  def getMinMaxAvg(listOfMeasures: Iterable[String]): List[Int] = {
    val listToParse = listOfMeasures.filter(_ != "NaN").map(_.toInt)
    if (listToParse.nonEmpty) {
      val max = listToParse.max
      val min = listToParse.min
      val avg = listToParse.sum / listToParse.size
      List(min, avg, max)
    }
    else Nil
  }

  def printTheStats(stats: Stats): Unit = {
    println("************ Program Output Start ************")
    println(s"Num of processed files: ${stats.numFiles}")
    println(s"Num of processed measurements: ${stats.numOfRecords}")
    println(s"Num of failed measurements: ${stats.numOfFailedRecords}\n")
    println("Sensors with highest avg humidity:\n")
    println("sensor-id,min,avg,max")
    stats.deviceWithSortedMinAvgMax.foreach(println)
    stats.deviceReportedWithOnlyNans.foreach(x => println(x + ",NaN,NaN,NaN"))
    println("************ Program Output End ************")
  }


  def getStatsFromFiles(dirPath: String) = {
    val (numFiles, futLines) = getLinesFromCSVFiles(dirPath)
    //val conf: SparkConf = new SparkConf()
    val sc: SparkContext = new SparkContext("local[*]", "sensor-nws")
    futLines.map { lines =>
      val readings: RDD[String] = sc
        .parallelize(lines.toSeq)
        .map(x => x.trim)
        .filter(x => x != "" && x != "sensor-id,humidity" && x != "\n")

      val splitEachString: RDD[(String, Iterable[String])] = readings
        .map(x => x.split(",").toList)
        .filter(_.nonEmpty)
        .groupBy { case x :: _ => x }
        .mapValues(x => x.map(y => y(1)))
        .persist()

      val numOfRecords: Long = splitEachString
        .mapValues(x => x.size)
        .values
        .sum()
        .toLong

      val numOfFailedRecords: Long = splitEachString
        .mapValues(y => y.count(_ == "NaN"))
        .values
        .sum()
        .toLong

      val deviceStats: RDD[(String, List[Int])] = splitEachString
        .mapValues(x => getMinMaxAvg(x))
        .persist()

      val deviceWithSortedMinAvgMax: Array[String] = deviceStats
        .filter(_._2.nonEmpty)
        .sortBy(-_._2(1))
        .map { case (x, y) => x + "," + y.mkString(",") }
        .collect()

      val deviceReportedWithOnlyNans: Array[String] = deviceStats.filter(_._2.isEmpty).keys.collect()
      sc.stop()
      Stats(numFiles, numOfRecords, numOfFailedRecords, deviceWithSortedMinAvgMax, deviceReportedWithOnlyNans)
    }
  }

  def main(args: Array[String]): Unit = {
    // println("Enter the directory path")
    //https://stackoverflow.com/questions/74132055/removing-run-fork-true-from-sbt-results-in-runtime-notfound-exception-a
    val dir = "/Users/ajitkumar/Downloads/flice/data/" //scala.io.StdIn.readLine()
    val futStats = getStatsFromFiles(dir)
    futStats.onComplete {
      case Success(stats) => printTheStats(stats)
      case Failure(e) => println(s"For ${e.getMessage} reasons the file could not be processed.")
    }
  }
}


