import org.apache.spark.{SparkConf, SparkContext}
import geotrellis.spark.io.hadoop._
object ReadGeoTiffSpark {
  def main(args: Array[String]): Unit = {
    val path: String = "F:/data/class_5.tif"
    val conf = new SparkConf().setAppName("testSpark").setMaster("local[*]")
    val sc: SparkContext = new SparkContext(conf)
    val rdd = sc.hadoopMultibandGeoTiffRDD(path)
  }
}
