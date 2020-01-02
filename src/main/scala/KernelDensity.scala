import geotrellis.proj4.LatLng
import geotrellis.vector.{Extent, Feature, Point, PointFeature}
import geotrellis.raster._
import geotrellis.raster.mapalgebra.focal.Kernel
import geotrellis.raster.render._
import geotrellis.spark.tiling.LayoutDefinition

import scala.util.Random

object KernelDensity {
  def main(args: Array[String]): Unit = {
    val extent = Extent(-109, 37, -102, 41)
    //这是科罗拉多州的范围
    def randomPointFeature(extent: Extent): PointFeature[Double] = {
      //根据给定范围产生随机值
      def randInRange(low: Double, high: Double): Double = {
        val x = Random.nextDouble()
        low * (1 - x) + high * x
      }
      //产生权重随机在[0.32)的随机点
      Feature(Point(randInRange(extent.xmin, extent.xmax), randInRange(extent.ymin, extent.ymax))
        , Random.nextInt() % 16 + 16)
    }
    val pts = (for (i <- 1 to 1000) yield randomPointFeature(extent)).toList
    println(pts)

    val kernelWidth: Int = 9

    /* Gaussian kernel with std. deviation 1.5, amplitude 25 */
    val kern: Kernel = Kernel.gaussian(kernelWidth, 1.5, 25)

    val kde: Tile = pts.kernelDensity(kern, RasterExtent(extent, 700, 400))


    val colorMap = ColorMap(
      (0 to kde.findMinMax._2 by 4).toArray,
      ColorRamps.HeatmapBlueToYellowToRedSpectrum
    )

    kde.renderPng(colorMap).write("test.png")

    import geotrellis.raster.io.geotiff._

    GeoTiff(kde, extent, LatLng).write("test.tif")


    val tl = TileLayout(7, 4, 100, 100)
    val ld = LayoutDefinition(extent, tl)

    def pointFeatureToExtent[D](kwidth: Double, ld: LayoutDefinition, ptf: PointFeature[D]): Extent = {
      val p = ptf.geom

      Extent(p.x - kwidth * ld.cellwidth / 2,
        p.y - kwidth * ld.cellheight / 2,
        p.x + kwidth * ld.cellwidth / 2,
        p.y + kwidth * ld.cellheight / 2)
    }

    def ptfToExtent[D](p: PointFeature[D]) = pointFeatureToExtent(9, ld, p)

    import geotrellis.spark._

    def ptfToSpatialKey[D](ptf: PointFeature[D]): Seq[(SpatialKey,PointFeature[D])] = {
      val ptextent = ptfToExtent(ptf)
      val gridBounds = ld.mapTransform(ptextent)

      for {
        (c, r) <- gridBounds.coordsIter.toList
        if r < ld.tileLayout.layoutRows && r >= 0//这个改了官网的例子
        if c < ld.tileLayout.layoutCols && c >= 0
      } yield (SpatialKey(c,r), ptf)
    }

    val keyfeatures: Map[SpatialKey, List[PointFeature[Double]]] =
      pts
        .flatMap(ptfToSpatialKey)
        .groupBy(_._1)
        .map { case (sk, v) => (sk, v.unzip._2) }

    val keytiles = keyfeatures.map { case (sk, pfs) =>
      (sk, pfs.kernelDensity(
        kern,
        RasterExtent(ld.mapTransform(sk), tl.tileDimensions._1, tl.tileDimensions._2)
      ))
    }

    import geotrellis.spark.stitch.TileLayoutStitcher

    val tileList =
      for {
        r <- 0 until ld.layoutRows
        c <- 0 until ld.layoutCols
      } yield {
        val k = SpatialKey(c,r)
        (k, keytiles.getOrElse(k, IntArrayTile.empty(tl.tileCols, tl.tileRows)))
      }

    val stitched = TileLayoutStitcher.stitch(tileList)._1
    stitched.renderPng(colorMap).write("test1.png")

    GeoTiff(stitched, extent, LatLng).write("test1.tif")


  }

}
