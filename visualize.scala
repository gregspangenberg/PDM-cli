//> using scala "3.3"
//> using dep "ch.unibas.cs.gravis::scalismo-ui:0.92.0"

import scalismo.io.StatisticalModelIO
import scalismo.ui.api.ScalismoUI
import scalismo.geometry._
import scalismo.common._
import java.io.File

object VisualizePDM extends App {

  // Initialize native libraries (important for Scalismo)
  scalismo.initialize()

  // Create a visualizer UI
  val ui = ScalismoUI("PDM Visualizer")

  // Create a group in the UI for visualization
  val modelGroup = ui.createGroup("pdm")

  try {
    // Load the PDM from the specified file path
    // Replace the path with your actual file path
    val loadedPdm = StatisticalModelIO
      .readStatisticalPointModel3D(
        new File("/home/greg/projects/scalismo_fit/models/humeri_pdm.h5.json")
      )
      .get

    // Display information about the loaded model
    println(s"Loaded PDM with ${loadedPdm.rank} principal components")

    // Visualize the mean of the PDM
    val meanView = ui.show(modelGroup, loadedPdm.mean, "mean")

    // Visualize the statistical model
    val modelView = ui.show(modelGroup, loadedPdm, "PDM")

    println("PDM loaded and visualized successfully")

  } catch {
    case e: Exception =>
      println(s"Error loading or visualizing the PDM: ${e.getMessage}")
      e.printStackTrace()
  }
}
