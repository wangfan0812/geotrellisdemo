import geotrellis.raster.io.geotiff.reader.GeoTiffReader
import geotrellis.raster.io.geotiff._



object readgeotiff {
  def main(args: Array[String]): Unit = {
    val path: String = "F:/data/class_5.tif"
    val geoTiff: MultibandGeoTiff = GeoTiffReader.readMultiband(path)
    println(geoTiff.bandCount)
    println(geoTiff.crs)
    println(geoTiff.extent)
  }
}
