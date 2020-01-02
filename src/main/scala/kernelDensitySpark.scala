import geotrellis.proj4.LatLng
import geotrellis.raster.{ArrayTile, DoubleCellType, MutableArrayTile, RasterExtent, Tile, TileLayout}
import geotrellis.spark.{ContextRDD, KeyBounds, SpatialKey, TileLayerMetadata}
import geotrellis.spark.tiling.LayoutDefinition
import geotrellis.vector.{Extent, Feature, Point, PointFeature}
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}
import geotrellis.raster._

import scala.util.Random
import geotrellis.raster.density.KernelStamper
import geotrellis.raster.io.geotiff.GeoTiff
import geotrellis.raster.mapalgebra.focal.Kernel



object kernelDensitySpark {
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

    val conf = new SparkConf().setMaster("local").setAppName("Kernel Density")
    val sc = new SparkContext(conf)
    val pointRdd = sc.parallelize(pts, 10)

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

    def ptfToSpatialKey[D](ptf: PointFeature[D]): Seq[(SpatialKey,PointFeature[D])] = {
      val ptextent = ptfToExtent(ptf)
      val gridBounds = ld.mapTransform(ptextent)

      for {
        (c, r) <- gridBounds.coordsIter.toList
        if r < ld.tileLayout.layoutRows && r >= 0//这个改了官网的例子
        if c < ld.tileLayout.layoutCols && c >= 0
      } yield (SpatialKey(c,r), ptf)
    }

    def stampPointFeature(
                           tile: MutableArrayTile,
                           tup: (SpatialKey, PointFeature[Double])
                         ): MutableArrayTile = {
      val (spatialKey, pointFeature) = tup
      val tileExtent = ld.mapTransform(spatialKey)
      val re = RasterExtent(tileExtent, tile)
      val result = tile.copy.asInstanceOf[MutableArrayTile]

      KernelStamper(result, kern)
        .stampKernelDouble(re.mapToGrid(pointFeature.geom), pointFeature.data)

      result
    }

    import geotrellis.raster.mapalgebra.local.LocalTileBinaryOp

    object Adder extends LocalTileBinaryOp {

      def combine(z1: Int, z2: Int) = {
        if (isNoData(z1)) {
          z2
        } else if (isNoData(z2)) {
          z1
        } else {
          z1 + z2
        }
      }

      def combine(r1: Double, r2:Double) = {
        if (isNoData(r1)) {
          r2
        } else if (isNoData(r2)) {
          r1
        } else {
          r1 + r2
        }
      }
    }

    def sumTiles(t1: MutableArrayTile, t2: MutableArrayTile): MutableArrayTile = {
      Adder(t1, t2).asInstanceOf[MutableArrayTile]
    }

    val tileRdd: RDD[(SpatialKey, Tile)] =
      pointRdd.flatMap(ptfToSpatialKey).mapPartitions({ partition =>
        partition.map { case (spatialKey, pointFeature) =>
          (spatialKey, (spatialKey, pointFeature))
        }
      }, preservesPartitioning = true)
        .aggregateByKey(ArrayTile.empty(DoubleCellType,ld.tileCols, ld.tileRows))(stampPointFeature, sumTiles)
        .mapValues { tile: MutableArrayTile => tile.asInstanceOf[Tile] }

    val metadata = TileLayerMetadata(DoubleCellType,
      ld,
      ld.extent,
      LatLng,
      KeyBounds(SpatialKey(0,0),
        SpatialKey(ld.layoutCols-1,
          ld.layoutRows-1)))
    val resultRdd = ContextRDD(tileRdd, metadata)
    resultRdd.foreach(result => {
      val col = result._1._1
      val row = result._1._2
      if(col >=0 && col < 7 && row >= 0 && row < 4) {
        GeoTiff(result._2, ld.mapTransform(result._1), LatLng)
          .write( row + "," + col + ".tif")
      }
    })
  }

}
