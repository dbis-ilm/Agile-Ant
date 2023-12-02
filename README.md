# Agile-Ant

This repo contains the source code of [Apache Spark 3.1.2](https://archive.apache.org/dist/spark/spark-3.1.2/spark-3.1.2.tgz) and the code for our Agile-Ant paper submission to VLDB 2024. 


## Organization

- spark-3.1.2: source code of Agile-Ant and Spark (including our modifications to the Spark source files) 
- reproducibility: scripts for running the 21 applications using Agile-Ant along with the data and data generators. 
- DAGs: this directory contains the DAGs of the 21 applications we evaluated in the paper. The DAGs are generated using the SparkCAD[8] tool ([https://github.com/dbis-ilm/SparkCAD](https://github.com/dbis-ilm/SparkCAD)). The applications visualized in these DAGs are run using the default caching decisions of the libraries we used (e.g. Spark MLlib, GraphFrames, etc).
