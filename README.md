# PDM-cli: Point Distribution Model Building and Fitting Tool

A command-line tool for building, fitting, and visualizing Point Distribution Models (PDMs) using the Scalismo library.

## Overview

PDM-cli provides a streamlined workflow for statistical shape modeling:

1. **Build PDMs** from training datasets
2. **Fit PDMs** to new data using posterior or ICP approaches
3. **Visualize** models and fitting results


## Usage
### Build PDM
```bash
scala-cli run build.scala -- /path/to/your/training/data
```
The tool will:
- Load point clouds from the specified directory
- Compute the mean shape
- Build a statistical model using PCA
- Save the model to the models directory




### Fit PDM
```bash
# Posterior-based fitting (for indexed point clouds that are algined and have correspondences)
scala-cli run fit.scala -- posterior --pdm models/your_pdm.h5.json --input /path/to/target/data

# ICP-based fitting (for point clouds that are aligned but do not have correspondences)
scala-cli run fit.scala -- icp --pdm models/your_pdm.h5.json --input /path/to/target/data
```
The tool will:
- Load the PDM from the specified file
- Load the from target point cloud folder
- Fit the PDM to the target data using the specified method (posterior or ICP)
- Save the fitted model to the output directory


Additional options:
- `--output <dir>` - Custom output directory
- `--iterations <number>` - Number of ICP iterations (default: 20)
- `--visualize` - Enable UI visualization during fitting

Note: The posterior-based fitting requires indexed point clouds, while the ICP-based fitting can be used with any point cloud.


### Visualize PDM
```bash
scala-cli run visualize.scala -- /path/to/your/pdm.h5.json
```
The tool will:
- Load the PDM from the specified file
- Visualize the mean shape and modes of variation



## Dependencies

This project uses [Scalismo](https://scalismo.org/), a library for statistical shape modeling.

Dependencies are managed through Scala CLI directives at the top of each file:

```scala
//> using scala "3.3"
//> using dep "ch.unibas.cs.gravis::scalismo-ui:0.92.0"
```
