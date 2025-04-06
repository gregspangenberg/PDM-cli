//> using scala "3.3"
//> using dep "ch.unibas.cs.gravis::scalismo-ui:0.92.0"

import scalismo.geometry.{Point, EuclideanVector, _3D}
import scalismo.common._
import scalismo.common.interpolation._
import scalismo.statisticalmodel._
import scalismo.statisticalmodel.dataset.DataCollection
import scalismo.utils.Random
import scalismo.io.StatisticalModelIO
import java.io.File
import scala.io.Source

@main
def buildPDM(dataPath: String = ""): Unit = {
  // Initialize Scalismo
  implicit val rng: Random = Random(42)
  scalismo.initialize()

  // Step 1: Load point clouds - using command line argument for data directory
  val dataDir = new File(dataPath)

  // Verify the directory exists
  if (!dataDir.exists() || !dataDir.isDirectory) {
    println(s"Error: ${dataDir.getAbsolutePath} is not a valid directory.")
    sys.exit(1)
  }

  println(s"Loading point clouds from ${dataDir.getAbsolutePath}")
  val pointCloudFiles = dataDir.listFiles().filter(_.getName.endsWith(".pts"))

  val allPointClouds = pointCloudFiles.map { file =>
    loadPointclouds(file)
  }.toIndexedSeq

  // Step 2: Choose the first point cloud as reference
  val referencePoints = computeMeanPointCloud(allPointClouds)
  val referenceDomain = UnstructuredPointsDomain(referencePoints)

  // Step 3: Convert point clouds to deformation fields
  val deformationFields = allPointClouds.map { points =>
    val deformations = points.zip(referencePoints).map { case (pt, refPt) =>
      pt - refPt
    }
    DiscreteField(referenceDomain, deformations)
  }

  // Step 4: Build the PDM using PCA
  val dataset = DataCollection(deformationFields.toIndexedSeq)
  val pdm = PointDistributionModel.createUsingPCA(dataset)

  // Print PDM information
  println(s"PDM built successfully with ${pdm.rank} principal components")

  // Step 5: Save the PDM to a file
  val outputDir = new File("models")
  if (!outputDir.exists()) {
    outputDir.mkdirs()
  }

  // Use the directory name as the basis for the output filename
  val dirName = dataDir.getName
  val outputFile = new File(outputDir, s"${dirName}_pdm.h5.json")

  // Use the specialized method for UnstructuredPointsDomain PDMs
  val saveResult =
    StatisticalModelIO.writeStatisticalPointModel3D(pdm, outputFile)

  saveResult match {
    case scala.util.Success(_) =>
      println(s"PDM successfully saved to: ${outputFile.getAbsolutePath}")
    case scala.util.Failure(ex) =>
      println(s"Failed to save PDM: ${ex.getMessage}")
  }
}

def loadPointclouds(file: File): IndexedSeq[Point[_3D]] = {
  val source = Source.fromFile(file)
  try {
    val points = source
      .getLines()
      .flatMap { line =>
        val trimmedLine = line.trim
        if (trimmedLine.isEmpty) None
        else {
          val parts = trimmedLine.split("\\s+")
          if (parts.length == 3) {
            val x = parts(0).toDouble
            val y = parts(1).toDouble
            val z = parts(2).toDouble
            Some(Point(x, y, z))
          } else None
        }
      }
      .toIndexedSeq
    points
  } finally { source.close() }
}

def computeMeanPointCloud(
    pointClouds: Seq[IndexedSeq[Point[_3D]]]
): IndexedSeq[Point[_3D]] = {
  // Check if input is not empty
  if (pointClouds.isEmpty) {
    throw new IllegalArgumentException(
      "Cannot compute mean of empty point cloud collection"
    )
  }

  // Initialize with zeros
  val zeroPoints =
    Array.fill(pointClouds.head.length)(EuclideanVector(0, 0, 0))

  // Sum all points
  val summedVectors = pointClouds.foldLeft(zeroPoints) { (acc, pointCloud) =>
    acc.zip(pointCloud).map { case (accVec, pt) =>
      accVec + EuclideanVector(pt.x, pt.y, pt.z)
    }
  }

  // Divide by count to get mean
  val meanVectors = summedVectors.map(_ * (1.0 / pointClouds.length))

  // Convert back to points
  meanVectors.map(v => Point(v.x, v.y, v.z)).toIndexedSeq
}
