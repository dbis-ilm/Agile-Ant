# Reproducibility


## Prerequisites
- A cluster of nodes
- Hadoop 2.7
- Spark 3.1.2

## Configuration

We added a `config.ini` file for the reviewers to set the cluster specifications e.g. the cluster size, the memory and number of cores of the instances etc. 

## Setup
    ./setup.sh

This script adds the AgileAnt directory in the HDFS root and put the jars of Spark (including Agile-Ant) in `/AgileAnt/Jars`. The jars are taken from [../spark-3.1.2/assembly/target/scala-2.12/jars](../spark-3.1.2/assembly/target/scala-2.12/jars), generated using the build scripts in [../spark-3.1.2](../spark-3.1.2).

## Generate Data
    ./prepare-data.sh

This script puts the required data from the `Data` directory to the `/AgileAnt/Data` directory on HDFS. `Data` contains the real-world datasets in Table 1 of the paper. The script also generates the synthetic data using HiBench.

To run the hadoop MapReduce applications that HiBench uses to generate the data for the SPC, WC, Sort and KM applications, the properties in the yarn-site.xml file in the Hadoop configuration directory should be

	<property>
		<name>yarn.nodemanager.aux-services</name>
		<value>mapreduce_shuffle</value>
	</property>
	<property>
		<name>yarn.nodemanager.aux-services.mapreduce_shuffle.class</name>
		<value>org.apache.hadoop.mapred.ShuffleHandler</value>
	</property>

## Agile-Ant Runs
    ./agile-ant-runs.sh

This script executes the Agile-Ant runs (introduced in Section 9.3 of the paper) on the data stored in the `/AgileAnt/Data` directory using the binaries in the `binaries` directory on HDFS, based on the cluster configuration file `config.ini`.

To execute the Agile-Ant runs, the cluster should support dynamic resource allocation. This requires setting up the external shuffle service in each node and modifying the yarn-site.xml as follows

	<property>
		<name>yarn.nodemanager.aux-services</name>
		<value>spark_shuffle</value>
	</property>
	<property>
		<name>yarn.nodemanager.aux-services.spark_shuffle.class</name>
		<value>org.apache.spark.network.yarn.YarnShuffleService</value>
	</property>

For further details, see [https://spark.apache.org/docs/latest/job-scheduling.html#dynamic-resource-allocation](https://spark.apache.org/docs/latest/job-scheduling.html#dynamic-resource-allocation).

Each time the yarn-site.xml file is updated, Hadoop and Yarn should be restarted. To do so, run the following scripts located in the Hadoop home directory on the master node:

    ./stop-all.sh
    ./start-all.sh

After restarting Hadoop and Yarn, the HDFS might be in the safe mode that does not allow writing to the HDFS. The following command turns the safe mode off:

    hdfs dfsadmin -safemode leave
