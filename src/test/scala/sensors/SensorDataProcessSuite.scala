package sensors

import junit.framework.TestCase.assertEquals

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}


class SensorDataProcessSuite extends munit.FunSuite {
  val dirPath = "../sensor-nws/src/test/resources/"

  def initializeSensorDataProcess(): Boolean = {
    try {
      SensorDataProcess
      true
    }
    catch {
      case ex: Throwable =>
        println
        (ex.getMessage)
        ex.printStackTrace()
        false
    }
  }

  import SensorDataProcess._

  test("'getLinesFromCSVFiles' should work for number of files and lines in files") {
    val expectedNumOfFiles = 2
    val (numFiles, futLines) = getLinesFromCSVFiles(dirPath)
    val unexpected = (numFiles - expectedNumOfFiles) == 0
    val noMatchString =
      s"""
         |Expected: $expectedNumOfFiles
         |Actual:   $numFiles """.stripMargin

    assert(unexpected,
      s"""|$noMatchString
          |Number of files are different.""".stripMargin)

    val expectedNumLines = 10
    futLines.onComplete {
      case Success(stats) => {
        val actualLines = stats.length
        val unexpected = (actualLines - expectedNumLines) == 0
        val noMatchString =
          s"""
             |Expected: $expectedNumLines
             |Actual:   $actualLines """.stripMargin

        assert(unexpected,
          s"""|$noMatchString
              |Number of lines in files are different.""".stripMargin)
      }
      case Failure(e) => println(s"For ${e.getMessage} reasons the file could not be processed.")
    }
  }

  test("'getStatsFromFiles' should get the expected stats class.") {
    assert(initializeSensorDataProcess(), " Initialization Failed! Check what might be the issue?")
    val expectedStats = Stats(2, 7, 2, Array("s2,78,82,88", "s1,10,54,98"), Array("s3"))
    val futActualStats = getStatsFromFiles(dirPath)
    futActualStats.onComplete {
      case Success(actualStats) => {
        assertEquals(actualStats.numOfRecords, expectedStats.numOfRecords)
        assertEquals(actualStats.numOfFailedRecords, expectedStats.numOfFailedRecords)
        assertEquals(actualStats.deviceReportedWithOnlyNans.length, expectedStats.deviceReportedWithOnlyNans.length)
        assertEquals(actualStats.deviceWithSortedMinAvgMax.length, expectedStats.deviceWithSortedMinAvgMax.length)
      }
      case Failure(e) => println(s"For ${e.getMessage} reasons the file could not be processed.")
    }
  }
}