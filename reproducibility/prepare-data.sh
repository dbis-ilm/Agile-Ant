#! /bin/bash

## Prepare data

source config.ini

### Download real-world datasets

### unzip command is required. If not installed, use the following: sudo apt-get install unzip

### CC
### LP
### PR
### SCC

mkdir data
wget "https://nrvis.com/download/data/road/road-road-usa.zip" -P data
unzip data/road-road-usa.zip -d data
rm data/readme.html
rm data/road-road-usa.zip
sed -i '/%/d' data/road-road-usa.mtx
sed -i '1s/^/src dst\n /' data/road-road-usa.mtx


mkdir data/ACON
mkdir data/ACON/data
#wget -m "ftp://ftp.nlm.nih.gov/nlmdata/sample/medline/" -P data/ACON/data
#gzip -d data/ACON/data/ftp.nlm.nih.gov/nlmdata/sample/medline/medsamp2016a.xml.gz
#mv data/ACON/data/ftp.nlm.nih.gov/nlmdata/sample/medline/medsamp2016a.xml data/ACON/data/medline
#rm -r data/ACON/data/ftp.nlm.nih.gov
wget -m "https://zenodo.org/record/8218901/files/medline" -P data/ACON/data
mv data/ACON/data/zenodo.org/record/8218901/files/medline data/ACON/data/
rm -r data/ACON/data/zenodo.org

mkdir data/ADNT
mkdir data/ADNT/data
wget "http://kdd.org/cupfiles/KDDCupData/1999/kddcup.data.zip" -P data/ADNT/data
unzip data/ADNT/data/kddcup.data.zip -d data/ADNT/data
mv data/ADNT/data/kddcup.data.txt data/ADNT/data/kddcup.data
rm data/ADNT/data/kddcup.data.zip

mkdir data/PFC
mkdir data/PFC/data
wget "http://archive.ics.uci.edu/static/public/31/covertype.zip" -P data/PFC/data
unzip data/PFC/data/covertype.zip -d data/PFC/data/
gzip -d data/PFC/data/covtype.data.gz
rm data/PFC/data/covertype.zip data/PFC/data/covtype.info data/PFC/data/old_covtype.info

mkdir data/RM
mkdir data/RM/data
wget "https://storage.googleapis.com/aas-data-sets/profiledata_06-May-2005.tar.gz" -P data/RM/data
tar -xf data/RM/data/profiledata_06-May-2005.tar.gz -C data/RM/data
mv data/RM/data/profiledata_06-May-2005/* data/RM/data
rm data/RM/data/profiledata_06-May-2005.tar.gz
rm -r data/RM/data/profiledata_06-May-2005

hdfs dfs -rm -r /AgileAnt/Applications/Data/*
hdfs dfs -put data/* /AgileAnt/Applications/Data


### LIR
hdfs dfs -rm -r hdfs:/AgileAnt/Applications/HiBench/Linear/Input
spark-submit --class com.intel.hibench.sparkbench.ml.LinearRegressionDataGenerator --master yarn --num-executors $fullClusterSize --executor-cores $numberOfCoresPerExecutor --executor-memory $executorMemorySize --driver-memory $executorMemorySize --conf spark.default.parallelism=200 --conf spark.sql.shuffle.partitions=200 binaries/sparkbench-assembly-8.0-SNAPSHOT-dist.jar hdfs:/AgileAnt/Applications/HiBench/Linear/Input 200000 30000


### SVM
hdfs dfs -rm -r hdfs:/AgileAnt/Applications/HiBench/SVM/Input
spark-submit --class com.intel.hibench.sparkbench.ml.SVMDataGenerator --master yarn --num-executors $fullClusterSize --executor-cores $numberOfCoresPerExecutor --executor-memory $executorMemorySize --driver-memory $executorMemorySize --conf spark.default.parallelism=200 --conf spark.sql.shuffle.partitions=200 binaries/sparkbench-assembly-8.0-SNAPSHOT-dist.jar hdfs:/AgileAnt/Applications/HiBench/SVM/Input 50000 100000


### PCA
hdfs dfs -rm -r hdfs:/AgileAnt/Applications/HiBench/PCA/Input
spark-submit --class com.intel.hibench.sparkbench.ml.PCADataGenerator --master yarn --num-executors $fullClusterSize --executor-cores $numberOfCoresPerExecutor --executor-memory $executorMemorySize --driver-memory $executorMemorySize --conf spark.default.parallelism=200 --conf spark.sql.shuffle.partitions=200 binaries/sparkbench-assembly-8.0-SNAPSHOT-dist.jar hdfs:/AgileAnt/Applications/HiBench/PCA/Input 15000 150000

### LDA
hdfs dfs -rm -r hdfs:/AgileAnt/Applications/HiBench/LDA/Input
spark-submit --class com.intel.hibench.sparkbench.ml.LDADataGenerator --master yarn --num-executors $fullClusterSize --executor-cores $numberOfCoresPerExecutor --executor-memory $executorMemorySize --driver-memory $executorMemorySize --conf spark.default.parallelism=200 --conf spark.sql.shuffle.partitions=200 binaries/sparkbench-assembly-8.0-SNAPSHOT-dist.jar hdfs:/AgileAnt/Applications/HiBench/LDA/Input 10000 3000 50 10000

### LR
hdfs dfs -rm -r hdfs:/AgileAnt/Applications/HiBench/LR/Input
spark-submit --class com.intel.hibench.sparkbench.ml.LogisticRegressionDataGenerator --master yarn --num-executors $fullClusterSize --executor-cores $numberOfCoresPerExecutor --executor-memory $executorMemorySize --driver-memory $executorMemorySize --conf spark.default.parallelism=200 --conf spark.sql.shuffle.partitions=200 binaries/sparkbench-assembly-8.0-SNAPSHOT-dist.jar hdfs:/AgileAnt/Applications/HiBench/LR/Input 10000 100000

### RF
hdfs dfs -rm -r hdfs:/AgileAnt/Applications/HiBench/RF/Input
spark-submit --class com.intel.hibench.sparkbench.ml.RandomForestDataGenerator --master yarn --num-executors $fullClusterSize --executor-cores $numberOfCoresPerExecutor --executor-memory $executorMemorySize --driver-memory $executorMemorySize --conf spark.default.parallelism=200 --conf spark.sql.shuffle.partitions=200 binaries/sparkbench-assembly-8.0-SNAPSHOT-dist.jar hdfs:/AgileAnt/Applications/HiBench/RF/Input 1000000 1000

### GBT
hdfs dfs -rm -r hdfs:/AgileAnt/Applications/HiBench/GBT/Input
spark-submit --class com.intel.hibench.sparkbench.ml.GradientBoostedTreeDataGenerator --master yarn --num-executors $fullClusterSize --executor-cores $numberOfCoresPerExecutor --executor-memory $executorMemorySize --driver-memory $executorMemorySize --conf spark.default.parallelism=200 --conf spark.sql.shuffle.partitions=200 binaries/sparkbench-assembly-8.0-SNAPSHOT-dist.jar hdfs:/AgileAnt/Applications/HiBench/GBT/Input 1000 12000

### ALS
hdfs dfs -rm -r hdfs:/AgileAnt/Applications/HiBench/ALS/Input
spark-submit --class com.intel.hibench.sparkbench.ml.RatingDataGenerator --master yarn --num-executors $fullClusterSize --executor-cores $numberOfCoresPerExecutor --executor-memory $executorMemorySize --driver-memory $executorMemorySize --conf spark.default.parallelism=200 --conf spark.sql.shuffle.partitions=200 binaries/sparkbench-assembly-8.0-SNAPSHOT-dist.jar hdfs:/AgileAnt/Applications/HiBench/ALS/Input 100000 100000 200000 true

### NW
hdfs dfs -rm -r hdfs:/AgileAnt/Applications/HiBench/NWeight/Input
spark-submit --class com.intel.hibench.sparkbench.graph.nweight.NWeightDataGenerator --master yarn --num-executors $fullClusterSize --executor-cores $numberOfCoresPerExecutor --executor-memory $executorMemorySize --driver-memory $executorMemorySize --conf spark.default.parallelism=200 --conf spark.sql.shuffle.partitions=200 binaries/sparkbench-assembly-8.0-SNAPSHOT-dist.jar /home/haal2895/AutoCaching/HiBench-master/sparkbench/graph/src/main/resources/nweight-user-features hdfs:/AgileAnt/Applications/HiBench/NWeight/Input 30000000

### WC
hdfs dfs -rm -r hdfs:/AgileAnt/Applications/HiBench/Wordcount/Input
hadoop --config $hadoopConfDir jar binaries/hadoop-mapreduce-examples-3.2.2.jar randomtextwriter -D mapreduce.randomtextwriter.totalbytes=32000000000 -D mapreduce.randomtextwriter.bytespermap=160000000 -D mapreduce.job.maps=200 -D mapreduce.job.reduces=200 hdfs:/AgileAnt/Applications/HiBench/Wordcount/Input

### Sort
hdfs dfs -rm -r hdfs:/AgileAnt/Applications/HiBench/Sort/Input
hadoop --config $hadoopConfDir jar binaries/hadoop-mapreduce-examples-3.2.2.jar randomtextwriter -D mapreduce.randomtextwriter.totalbytes=10000000000 -D mapreduce.randomtextwriter.bytespermap=50000000 -D mapreduce.job.maps=200 -D mapreduce.job.reduces=200 hdfs:/AgileAnt/Applications/HiBench/Sort/Input

### SPR
hdfs dfs -rm -r hdfs:/AgileAnt/Applications/HiBench/Pagerank/Input
hadoop --config $hadoopConfDir jar /home/haal2895/AutoCaching/HiBench-master/autogen/target/autogen-8.0-SNAPSHOT-jar-with-dependencies.jar HiBench.DataGen -t pagerank -b hdfs:/AgileAnt/Applications/HiBench/Pagerank -n Input -m 200 -r 200 -p 5000000 -pbalance -pbalance -o text

### KM
hdfs dfs -rm -r hdfs:/AgileAnt/Applications/HiBench/Kmeans/Input
hadoop --config $hadoopConfDir jar /home/haal2895/AutoCaching/HiBench-master/autogen/target/autogen-8.0-SNAPSHOT-jar-with-dependencies.jar org.apache.mahout.clustering.kmeans.GenKMeansDataset -D hadoop.job.history.user.location=hdfs:/AgileAnt/Applications/HiBench/Kmeans/Input/samples -sampleDir hdfs:/AgileAnt/Applications/HiBench/Kmeans/Input/samples -clusterDir hdfs:/AgileAnt/Applications/HiBench/Kmeans/Input/cluster -numClusters 5 -numSamples 100000000 -samplesPerFile 20000000 -sampleDimension 20

