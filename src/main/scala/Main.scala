package demo

import geotrellis.raster._
import geotrellis.raster.mapalgebra.focal.Square
//import geotrellis.spark._

object Main {
  def helloSentence = "Hello GeoTrellis"

  def helloRaster(): Unit = {
    val nd = NODATA    //-2147483648

    val input = Array[Int](
      nd, 7, 1, 1, 3, 5, 9, 8, 2,
      9, 1, 1, 2, 2, 2, 4, 3, 5,
      3, 8, 1, 3, 3, 3, 1, 2, 2,
      2, 4, 7, 1, nd, 1, 8, 4, 3)

    //将数组转化为4*9矩阵
    val iat = IntArrayTile(input, 9, 4)

    //用一个n*n的窗口对矩阵做卷积，设中心值为平均值
    //Square(i) => n = 2 * i + 1
    val focalNeighborhood = Square(1)
    println(focalNeighborhood)
    val meanTile = iat.focalMean(focalNeighborhood)

    for (i <- 0 to 3) {
      for (j <- 0 to 8) {
        print(meanTile.getDouble(j, i) + " ")
      }
      println()
    }
  }

  def main(args: Array[String]): Unit = {
    helloRaster()
  }
}