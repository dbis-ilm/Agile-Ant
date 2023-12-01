# Agile-Ant

This repo contains the source code of [Apache Spark 3.1.2](https://archive.apache.org/dist/spark/spark-3.1.2/spark-3.1.2.tgz) and the code for our Agile-Ant paper submission to VLDB 2024. 

The code of Agile-Ant is located in the directory [core/src/main/scala/org/apache/spark/agile_ant](core/src/main/scala/org/apache/spark/agile_ant). We also modified some of the Spark source files to enable Agile-Ant interact with Spark. All the Spark source files we modified are in the core of Spark [core/src/main/scala/org/apache/spark](core/src/main/scala/org/apache/spark). 

To facilitate checking our code for the reviewers,
- We annotate each Agile-Ant source file with a description starting with `Agile-Ant`
- To each single line of code we added in the Spark source files, we append a comment starting with `// Agile-Ant`
- For multiple lines of code that we added in the Spark source files, we put them between two lines of comments: `// Agile-Ant: Start` and `// Agile-Ant: End`. 

This way, a grep command on `Agile-Ant` would highlight all our code.


## Build

To build Spark (including Agile-Ant) wherther the reviwers can run a full build or only build the core.

### Full build:

    ./full-build.sh

### Core build:

    ./core-build.sh

The full-build builds the entire Spark project (including Spark MLlib, GraphX libraries etc.) with Agile-Ant while the core-build only builds the Spark core with Agile-Ant. On our machine, the full-build and the core-build take about 30 minutes and 3 minutes respectively.

Running the core-build at the beginning compiles sucessfully but there might be linking errors, for which a full-build would be required once at the beginning. The prerequisites are explained in [https://spark.apache.org/docs/3.1.2/building-spark.html](https://spark.apache.org/docs/3.1.2/building-spark.html)

The Spark jars created from the build are located in [assembly/target/scala-2.12/jars/](assembly/target/scala-2.12/jars/). We have already built Spark and stored the jars in [assembly/target/scala-2.12/jars/](assembly/target/scala-2.12/jars/). 

Note: The jars are stored afterward on the HDFS, to be used for Agile-Ant when submitting a Spark application run. See [../reproducibility](../reproducibility).
