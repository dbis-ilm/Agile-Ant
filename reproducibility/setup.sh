#! /bin/bash

## Setup

hdfs dfs -mkdir /AgileAnt;
hdfs dfs -mkdir /AgileAnt/ExecutionLogs # To store the execution logs. The logs can then be visualized using Spark History Server and SparkCAD
hdfs dfs -mkdir /AgileAnt/Applications;
hdfs dfs -mkdir /AgileAnt/Applications/Data;
hdfs dfs -mkdir /AgileAnt/Applications/HiBench;
hdfs dfs -mkdir /AgileAnt/Jars;

### Store the jars of Spark (including Agile-Ant) in the HDFS
hdfs dfs -put ../spark-3.1.2/assembly/target/scala-2.12/jars/*.jar /AgileAnt/Jars

# After a full build the jars shall be reloaded using:
# hdfs dfs -put -f ../spark3.1.2/assembly/target/scala-2.12/jars/*.jar /AgileAnt/Jars

# After a core build the core jar shall be reloaded using:
# hdfs dfs -put -f ../spark3.1.2/assembly/target/scala-2.12/jars/spark-core_2.12-3.1.2.jar /AgileAnt/Jars


### Download HiBench binary
wget "https://zenodo.org/record/8215657/files/sparkbench-assembly-8.0-SNAPSHOT-dist.jar" -P binaries
