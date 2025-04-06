//> using scala "3.3"
//> using dep "ch.unibas.cs.gravis::scalismo-ui:0.92.0"

import scalismo.io.StatisticalModelIO
import scalismo.ui.api.ScalismoUI
import scalismo.geometry._
import scalismo.common._
import scalismo.statisticalmodel._
import scalismo.utils.Random
import scalismo.utils.Random.implicits.randomGenerator
import java.io.File
import java.io.PrintWriter
import breeze.linalg.{DenseMatrix, DenseVector}
import scala.io.Source

/**
 * Combined PDM fitting tool that provides both posterior-based and ICP-based fitting
 * with support for processing directories of files.
 */
object PDMFittingTool {
  
  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      printUsage()
      System.exit(1)
    }
    
    // Initialize native libraries
    scalismo.initialize()
    implicit val rng: Random = Random(42)
    
    val command = args(0).toLowerCase
    
    command match {
      case "posterior" => runPosteriorFitting(args.drop(1))
      case "icp" => runICPFitting(args.drop(1))
      case "help" => printUsage()
      case _ => 
        System.err.println(s"Unknown command: $command")
        printUsage()
        System.exit(1)
    }
  }
  
  def printUsage(): Unit = {
    println("""
    |PDM Fitting Tool Usage:
    |
    |  posterior [options] - Fit PDM using posterior approach (requires indexed point clouds)
    |    Options:
    |      --pdm <path>           - Path to PDM file (required)
    |      --input <path>         - Path to point cloud file or directory (required)
    |      --output <dir>         - Output directory (default: 'fitted' in input directory)
    |      --visualize            - Show visualization UI (default: false)
    |
    |  icp [options] - Fit PDM using ICP approach
    |    Options:
    |      --pdm <path>           - Path to PDM file (required)
    |      --input <path>         - Path to point cloud file or directory (required)
    |      --output <dir>         - Output directory (default: 'fitted' in input directory) 
    |      --iterations <number>  - Number of ICP iterations (default: 20)
    |      --visualize            - Show visualization UI (default: false)
    |
    |  help - Show this help message
    """.stripMargin)
  }
  
  def parseOptions(args: Array[String]): Map[String, String] = {
    def loop(remainingArgs: List[String], acc: Map[String, String]): Map[String, String] = {
      remainingArgs match {
        case Nil => acc
        case key :: value :: rest if key.startsWith("--") => 
          loop(rest, acc + (key.drop(2) -> value))
        case opt :: rest if opt.startsWith("--") => 
          loop(rest, acc + (opt.drop(2) -> "true"))
        case x :: rest => 
          System.err.println(s"Ignoring unexpected argument: $x")
          loop(rest, acc)
      }
    }
    
    loop(args.toList, Map.empty)
  }
  
  // Run posterior-based fitting (for point clouds with indices)
  def runPosteriorFitting(args: Array[String]): Unit = {
    val options = parseOptions(args)
    
    val pdmPath = options.getOrElse("pdm", "")
    val inputPath = options.getOrElse("input", "")
    val outputDir = options.getOrElse("output", "")
    val visualize = options.getOrElse("visualize", "false").toBoolean
    
    if (pdmPath.isEmpty || inputPath.isEmpty) {
      System.err.println("Error: PDM path and input path are required for posterior fitting")
      printUsage()
      System.exit(1)
    }
    
    try {
      // Check if inputPath is a directory or a single file
      val inputFile = new File(inputPath)
      if (!inputFile.exists()) {
        throw new IllegalArgumentException(s"Input path does not exist: $inputPath")
      }
      
      // Create output directory if specified and doesn't exist
      val outputDirectory = if (outputDir.isEmpty) {
        if (inputFile.isDirectory) {
          new File(inputFile, "fitted")
        } else {
          new File(inputFile.getParent, "fitted")
        }
      } else {
        new File(outputDir)
      }
      
      if (!outputDirectory.exists()) {
        outputDirectory.mkdirs()
        println(s"Created output directory: ${outputDirectory.getAbsolutePath}")
      }
      
      // Load the PDM
      val pdm = StatisticalModelIO
        .readStatisticalPointModel3D(new File(pdmPath))
        .getOrElse {
          throw new RuntimeException(s"Failed to load PDM from $pdmPath")
        }
      
      println(s"Loaded PDM with ${pdm.rank} principal components")
      
      // Create UI if visualization is requested
      val ui = if (visualize) Some(ScalismoUI("PDM Posterior Fitting")) else None
      val modelGroup = ui.map(_.createGroup("model"))
      
      // Process files based on whether input is a directory or a single file
      val filesToProcess = if (inputFile.isDirectory) {
        inputFile.listFiles().filter(f => f.isFile && f.getName.endsWith(".pts"))
      } else {
        Array(inputFile)
      }
      
      println(s"Found ${filesToProcess.length} file(s) to process")
      
      // Process each file
      filesToProcess.foreach { file =>
        println(s"Processing file: ${file.getName}")
        try {
          processPointCloudWithPosterior(file, pdm, outputDirectory, ui, modelGroup)
        } catch {
          case e: Exception =>
            println(s"Error processing file ${file.getName}: ${e.getMessage}")
            // Continue with other files instead of exiting
        }
      }
      
      println(s"All files processed. Results saved to: ${outputDirectory.getAbsolutePath}")
      
    } catch {
      case e: Exception =>
        System.err.println(s"Error during posterior fitting: ${e.getMessage}")
        e.printStackTrace()
        System.exit(1)
    }
  }
  
  // Process a single point cloud file with posterior approach
  def processPointCloudWithPosterior(
    file: File, 
    pdm: PointDistributionModel[_3D, UnstructuredPointsDomain],
    outputDir: File,
    ui: Option[ScalismoUI],
    modelGroup: Option[scalismo.ui.api.Group]
  ): Unit = {
    // Load partial target point cloud with indices
    val (partialPoints, partialIndices) = loadPartialPointcloud(file)
    
    println(s"Loaded ${partialPoints.length} points (${partialPoints.length * 100.0 / pdm.reference.pointSet.numberOfPoints}% of full model)")
    
    // Create point observations for posterior calculation
    val pointObservations = partialIndices.zip(partialPoints).map { 
      case (idx, point) => 
        (PointId(idx), point, MultivariateNormalDistribution(DenseVector.zeros[Double](3), DenseMatrix.eye[Double](3)))
    }
    
    // Calculate the posterior model using the observations
    val posteriorModel = pdm.posterior(pointObservations)
    
    // Generate the fitted instance using the posterior mean
    val fittedInstance = posteriorModel.mean
    
    // Visualize results if requested
    if (ui.isDefined) {
      ui.foreach { u =>
        val meanView = u.show(modelGroup.get, pdm.mean, "Mean Shape")
        val partialPointsDomain = UnstructuredPointsDomain[_3D](partialPoints)
        val targetView = u.show(modelGroup.get, partialPointsDomain, s"Target: ${file.getName}")
        val fittedView = u.show(modelGroup.get, fittedInstance, s"Fitted: ${file.getName}")
        
        // Sample a few alternative completions from the posterior
        for (i <- 1 to 2) {
          val sample = posteriorModel.sample()(using implicitly[Random])
          u.show[UnstructuredPointsDomain[_3D]](modelGroup.get, sample, s"Posterior Sample ${file.getName} #$i")
        }
      }
    }
    
    // Generate output file name
    val outputFileName = {
      val baseName = file.getName.replaceAll("\\.pts$", "")
      s"${baseName}_fitted_posterior.pts"
    }
    
    val outputFile = new File(outputDir, outputFileName)
    
    // Write the fitted point cloud to file
    writePointCloud(fittedInstance.pointSet, outputFile)
    println(s"Fitted point cloud written to: ${outputFile.getAbsolutePath}")
  }
  
  // Run ICP-based fitting (for point clouds without indices)
  def runICPFitting(args: Array[String]): Unit = {
    val options = parseOptions(args)
    
    val pdmPath = options.getOrElse("pdm", "")
    val inputPath = options.getOrElse("input", "")
    val outputDir = options.getOrElse("output", "")
    val iterationsStr = options.getOrElse("iterations", "20")
    val visualize = options.getOrElse("visualize", "false").toBoolean
    
    val iterations = try {
      iterationsStr.toInt
    } catch {
      case e: NumberFormatException =>
        System.err.println(s"Warning: Could not parse '$iterationsStr' as a number. Using default of 20 iterations.")
        20
    }
    
    if (pdmPath.isEmpty || inputPath.isEmpty) {
      System.err.println("Error: PDM path and input path are required for ICP fitting")
      printUsage()
      System.exit(1)
    }
    
    try {
      // Check if inputPath is a directory or a single file
      val targetFile = new File(inputPath)
      if (!targetFile.exists()) {
        throw new IllegalArgumentException(s"Target path does not exist: $inputPath")
      }
      
      // Create output directory if specified and doesn't exist
      val outputDirectory = if (outputDir.isEmpty) {
        if (targetFile.isDirectory) {
          new File(targetFile, "fitted")
        } else {
          new File(targetFile.getParent, "fitted")
        }
      } else {
        new File(outputDir)
      }
      
      if (!outputDirectory.exists()) {
        outputDirectory.mkdirs()
        println(s"Created output directory: ${outputDirectory.getAbsolutePath}")
      }
      
      // Load the PDM
      val pdm = StatisticalModelIO
        .readStatisticalPointModel3D(new File(pdmPath))
        .getOrElse {
          throw new RuntimeException(s"Failed to load PDM from $pdmPath")
        }
      
      println(s"Loaded PDM with ${pdm.rank} principal components")
      
      // Create UI if visualization is requested
      val ui = if (visualize) Some(ScalismoUI("PDM ICP Fitting")) else None
      val modelGroup = ui.map(_.createGroup("model"))
      val targetGroup = ui.map(_.createGroup("target"))
      val resultGroup = ui.map(_.createGroup("results"))
      
      // Process files based on whether input is a directory or a single file
      val filesToProcess = if (targetFile.isDirectory) {
        targetFile.listFiles().filter(f => f.isFile && f.getName.endsWith(".pts"))
      } else {
        Array(targetFile)
      }
      
      println(s"Found ${filesToProcess.length} file(s) to process")
      
      // Process each file
      filesToProcess.foreach { file =>
        println(s"Processing file: ${file.getName}")
        try {
          processPointCloudWithICP(file, pdm, outputDirectory, iterations, ui, modelGroup, targetGroup, resultGroup)
        } catch {
          case e: Exception =>
            println(s"Error processing file ${file.getName}: ${e.getMessage}")
            // Continue with other files instead of exiting
        }
      }
      
      println(s"All files processed. Results saved to: ${outputDirectory.getAbsolutePath}")
      
    } catch {
      case e: Exception =>
        System.err.println(s"Error during ICP fitting: ${e.getMessage}")
        e.printStackTrace()
        System.exit(1)
    }
  }
  
  // Process a single point cloud file with ICP approach
  def processPointCloudWithICP(
    file: File, 
    pdm: PointDistributionModel[_3D, UnstructuredPointsDomain], 
    outputDir: File, 
    iterations: Int,
    ui: Option[ScalismoUI],
    modelGroup: Option[scalismo.ui.api.Group],
    targetGroup: Option[scalismo.ui.api.Group],
    resultGroup: Option[scalismo.ui.api.Group]
  ): Unit = {
    // Load target point cloud
    val targetPoints = loadSimplePointcloud(file)
    val targetPointSet = UnstructuredPointsDomain(targetPoints)
    
    // Visualize target and model if UI is enabled
    ui.foreach { u =>
      u.show(targetGroup.get, targetPointSet, s"Target: ${file.getName}")
      u.show(modelGroup.get, pdm.mean, "Model Mean")
    }
    
    // Select points of interest for correspondence
    val ptIds = targetPoints.map(point => pdm.reference.pointSet.findClosestPoint(point).id)
    
    // Define function to attribute correspondences between model and target points
    def attributeCorrespondences(movingShape: PointSet[_3D], ptIds: Seq[PointId]): Seq[(PointId, Point[_3D])] = {
      ptIds.map { id =>
        val pt = movingShape.point(id)
        // Find closest point in target point cloud
        val closestPoint = targetPointSet.pointSet.findClosestPoint(pt).point
        
        (id, closestPoint)
      }
    }
    
    // Define function to fit model to correspondences
    def fitModel(correspondences: Seq[(PointId, Point[_3D])]): PointSet[_3D] = {
      val regressionData = correspondences.map { case (id, point) =>
        (id, point, MultivariateNormalDistribution(
          DenseVector.zeros[Double](3), 
          DenseMatrix.eye[Double](3)
        ))
      }
      
      val posterior = pdm.posterior(regressionData.toIndexedSeq)
      posterior.mean.pointSet
    }
    
    // Define non-rigid ICP function
    def nonrigidICP(movingShape: PointSet[_3D], ptIds: Seq[PointId], numberOfIterations: Int): PointSet[_3D] = {
      if (numberOfIterations == 0) {
        movingShape
      } else {
        println(s"ICP iteration ${iterations - numberOfIterations + 1}/${iterations}")
        
        val correspondences = attributeCorrespondences(movingShape, ptIds)
        val transformed = fitModel(correspondences)
        
        // Visualize progress if UI is enabled (every 5th iteration or last one)
        if (ui.isDefined && (numberOfIterations % 5 == 0 || numberOfIterations == 1)) {
          ui.foreach(_.show(resultGroup.get, 
                           UnstructuredPointsDomain(transformed.points.toIndexedSeq), 
                           s"${file.getName} - Iteration ${iterations - numberOfIterations + 1}"))
        }
        
        nonrigidICP(transformed, ptIds, numberOfIterations - 1)
      }
    }
    
    // Run the non-rigid ICP with specified iterations
    val finalFit = nonrigidICP(pdm.mean.pointSet, ptIds, iterations)
    
    // Show final result if UI is enabled
    ui.foreach(_.show(resultGroup.get, 
                     UnstructuredPointsDomain(finalFit.points.toIndexedSeq), 
                     s"Final Fit: ${file.getName}"))
    
    // Generate output file name
    val outputFileName = {
      val baseName = file.getName.replaceAll("\\.pts$", "")
      s"${baseName}_fitted_icp.pts"
    }
    
    val outputFile = new File(outputDir, outputFileName)
    
    // Write the fitted point cloud to file
    writePointCloud(finalFit, outputFile)
    println(s"Fitted point cloud written to: ${outputFile.getAbsolutePath}")
  }
  
  // Write a point cloud to a .pts file in (n,3) format
  def writePointCloud(pointSet: PointSet[_3D], file: File): Unit = {
    val writer = new PrintWriter(file)
    try {
      // Get all points from the point set
      val points = pointSet.points
      
      // Write each point as "x y z" on a separate line
      points.foreach { point =>
        writer.println(f"${point.x}%.8f ${point.y}%.8f ${point.z}%.8f")
      }
    } finally {
      writer.close()
    }
  }
  
  // Load a point cloud with indices (format: x y z index)
  def loadPartialPointcloud(file: File): (IndexedSeq[Point[_3D]], IndexedSeq[Int]) = {
    if (!file.exists()) {
      throw new IllegalArgumentException(s"File not found: ${file.getAbsolutePath}")
    }
    
    val source = Source.fromFile(file)
    try {
      val lines = source.getLines().map(_.trim).filter(_.nonEmpty).toIndexedSeq
      
      // Check if all non-empty lines have 4 columns
      val invalidLines = lines.zipWithIndex.filter { case (line, _) => 
        line.split("\\s+").length != 4 
      }
      
      if (invalidLines.nonEmpty) {
        val (firstInvalidLine, lineNum) = invalidLines.head
        throw new IllegalArgumentException(
          s"Invalid pointcloud format in ${file.getName}: Line ${lineNum + 1} does not have 4 columns: '$firstInvalidLine'. " +
          "Expected format: x y z index"
        )
      }
      
      val pointsWithIndices = lines.map { line =>
        val parts = line.split("\\s+")
        val x = parts(0).toDouble
        val y = parts(1).toDouble
        val z = parts(2).toDouble
        val idx = parts(3).toInt
        (Point(x, y, z), idx)
      }
      
      // Separate points and indices
      val points = pointsWithIndices.map(_._1)
      val indices = pointsWithIndices.map(_._2)
      (points, indices)
    } finally { 
      source.close() 
    }
  }
  
  // Simple point cloud loading function for (n,3) data without indices
  def loadSimplePointcloud(file: File): IndexedSeq[Point[_3D]] = {
    if (!file.exists()) {
      throw new IllegalArgumentException(s"File not found: ${file.getAbsolutePath}")
    }
    
    val source = Source.fromFile(file)
    try {
      val points = source.getLines()
        .map(_.trim)
        .filter(_.nonEmpty)
        .map { line =>
          val parts = line.split("\\s+")
          if (parts.length >= 3) {
            val x = parts(0).toDouble
            val y = parts(1).toDouble
            val z = parts(2).toDouble
            Point(x, y, z)
          } else {
            throw new IllegalArgumentException(
              s"Invalid point format in ${file.getName}: '$line'. Expected at least 3 columns (x y z)."
            )
          }
        }
        .toIndexedSeq
      
      if (points.isEmpty) {
        throw new IllegalArgumentException(s"No valid points found in file ${file.getName}")
      }
      
      points
    } finally {
      source.close()
    }
  }
}